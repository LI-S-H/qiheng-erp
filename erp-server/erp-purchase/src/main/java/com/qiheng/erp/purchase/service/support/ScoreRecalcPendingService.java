package com.qiheng.erp.purchase.service.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.purchase.domain.supplierscore.constant.SupplierScoreConstants;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreEventTrigger;
import com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreFireMessage;
import com.qiheng.erp.purchase.mq.SupplierScoreRocketMQTemplate;
import com.qiheng.erp.purchase.mapper.SupplierScoreChangeLogMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 评分重算 pending 合并与调度服务。
 *
 * <p>由业务线程在业务事务提交后(AFTER_COMMIT)调用,职责:</p>
 * <ol>
 *   <li>加 supplier 维度分布式锁</li>
 *   <li>读 supplier:score:pending:{supplierId},不存在则创建首个 pending,
 *       存在则合并 sourceRefs 并增加 mergedCount</li>
 *   <li>首个事件 → 同步投递 SCHEDULED_FIRE 延迟消息(delayLevel=9,5 分钟)</li>
 *   <li>非首个事件 → 不发 MQ,只更新 Redis</li>
 *   <li>释放锁</li>
 * </ol>
 *
 * <p>MQ 消息只发 1 种(SCHEDULED_FIRE),由 Consumer 执行实际重算。
 * 合并逻辑由业务线程承担,不在 Consumer 端做。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rocketmq", name = "name-server")
public class ScoreRecalcPendingService {

    private final StringRedisTemplate stringRedisTemplate;
    private final RedissonClient redissonClient;
    private final SupplierScoreRocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final BillNoGenerator billNoGenerator;
    private final SupplierScoreChangeLogMapper changeLogMapper;

    @Value("${supplier-score.rocketmq.topic:erp-supplier-score-recalc}")
    private String mqTopic;

    /**
     * 业务事件合并入 pending + 必要时投递 SCHEDULED_FIRE 延迟消息。
     *
     * <p>必须在业务事务提交后调用(AFTER_COMMIT),不能在事务内调用,
     * 否则 Redis 操作不受事务保护且与主业务事务边界不一致。</p>
     *
     * @param trigger 业务事件触发请求
     * @return 本次事件所属的评分批次号；字段不完整时返回 null
     */
    public String mergeAndScheduleFire(SupplierScoreEventTrigger trigger) {
        if (trigger == null || trigger.getSupplierId() == null || trigger.getTriggerType() == null) {
            log.warn("mergeAndScheduleFire 跳过: 必填字段为空");
            return null;
        }
        Long supplierId = trigger.getSupplierId();
        String lockKey = SupplierScoreRedisKeys.lockKey(supplierId);
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired = false;
        try {
            // 业务事务已提交；不能因锁忙直接丢弃本次评分事件。
            lock.lock();
            acquired = true;
            return doMergeAndSchedule(trigger);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /** 在供应商锁内合并事件来源，首事件创建窗口并投递延迟消息，旧窗口重建时继承未处理来源。 */
    private String doMergeAndSchedule(SupplierScoreEventTrigger trigger) {
        // 1. 读取当前 pending 状态
        Long supplierId = trigger.getSupplierId();
        String pendingKey = SupplierScoreRedisKeys.pendingKey(supplierId);
        PendingSnapshot snapshot = readSnapshot(pendingKey);
        boolean isFirstEvent = (snapshot == null);
        List<PendingSnapshot.SourceRef> unprocessedRefs = List.of();
        // MQ 重投与新窗口并发由 supplier 锁串行，旧消息凭 batchNo 跳过。
        // 2. snapshot 存在 且已超过两个窗口时间未消费,合并旧消息,创建新窗口
        if (snapshot != null && snapshot.getScheduledFireAt() != null
                && System.currentTimeMillis() > snapshot.getScheduledFireAt() + SupplierScoreConstants.MERGE_WINDOW_MS) {
            // 新窗口必须继承旧来源，不能只计新订单；跨业务日由消费端完整校正。
            unprocessedRefs = snapshot.getSourceRefs() == null ? List.of() : List.copyOf(snapshot.getSourceRefs());
            snapshot = null;
            isFirstEvent = true;
        }
        // 3. 首个事件创建 pending，等待 5 分钟合并窗口结束；消费端按批次幂等。
        if (isFirstEvent) {
            // 3.1 首窗口预分配统一评分批次号；无评分变化或投递失败时允许留空号。
            String batchNo = billNoGenerator.nextNo(SupplierScoreRedisKeys.SC_BILL_PREFIX,
                    changeLogMapper::findMaxBatchNoSequence);
            long now = System.currentTimeMillis();
            long scheduledFireAt = now + SupplierScoreConstants.MERGE_WINDOW_MS;
            // 3.2 创建新 pending
            snapshot = new PendingSnapshot();
            snapshot.setBatchNo(batchNo);
            snapshot.setFirstEventAt(now);
            snapshot.setScheduledFireAt(scheduledFireAt);
            List<PendingSnapshot.SourceRef> refs = new ArrayList<>(unprocessedRefs);
            refs.add(toSourceRef(trigger));
            snapshot.setMergedCount(refs.size());
            snapshot.setSourceRefs(refs);
            // 3.3 写入 pending 状态
            writeSnapshot(pendingKey, snapshot);
            try {
                // 4. 投递延迟消息
                scheduleFire(supplierId, batchNo, scheduledFireAt);
            } catch (Exception e) {
                // 发送结果确定失败时释放 pending，后续事件才有机会再次投递。
                stringRedisTemplate.delete(pendingKey);
                // 本次已确认入库但没有可消费消息；下一次事件不得沿用缺少本订单的质量总额。
                stringRedisTemplate.delete(SupplierScoreRedisKeys.qualityAmountKey(supplierId));
                log.error("SCHEDULED_FIRE 投递异常 supplierId={}", supplierId, e);
                throw new IllegalStateException("SCHEDULED_FIRE 投递失败", e);
            }
            log.info("合并窗口首事件 supplierId={} batchNo={} 投递延迟消息 fireAt={}",
                    supplierId, batchNo, scheduledFireAt);
        } else {
            // 5. 不是窗口内的首次消息,后续事件:合并 sourceRefs + mergedCount++
            List<PendingSnapshot.SourceRef> refs = snapshot.getSourceRefs();
            if (refs == null) {
                refs = new ArrayList<>();
                snapshot.setSourceRefs(refs);
            }
            refs.add(toSourceRef(trigger));
            snapshot.setMergedCount(refs.size());
            writeSnapshot(pendingKey, snapshot);
            log.info("合并窗口后续事件 supplierId={} batchNo={} mergedCount={}",
                    supplierId, snapshot.getBatchNo(), snapshot.getMergedCount());
        }
        return snapshot.getBatchNo();
    }

    /** 同步发送五分钟延迟消息，生产者内建重试仍失败时将异常交给合并入口处理。 */
    private void scheduleFire(Long supplierId, String batchNo, long fireAt) throws Exception {
        SupplierScoreFireMessage msg = new SupplierScoreFireMessage();
        msg.setMsgType("SCHEDULED_FIRE");
        msg.setSupplierId(supplierId);
        msg.setBatchNo(batchNo);
        msg.setScheduledFireAt(fireAt);
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(msg);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("SCHEDULED_FIRE 消息序列化失败", e);
        }
        // 字节体沿用现有 JSON 序列化；Key 用批次号检索，消费幂等仍由锁、批次核对和日志键保证。
        Message<byte[]> message = MessageBuilder.withPayload(body)
                .setHeader(RocketMQHeaders.KEYS, batchNo).build();
        // 使用 4.x Broker 支持的延迟等级；沿用专用生产者的超时和内建重试，失败必须向外抛出。
        rocketMQTemplate.syncSend(mqTopic + ":SCHEDULED_FIRE", message,
                rocketMQTemplate.getProducer().getSendMsgTimeout(), SupplierScoreConstants.MQ_DELAY_LEVEL_5MIN);
    }

    /** 将业务事件转换为合并窗口来源，保留全部采购单标识供批量增量使用。 */
    private PendingSnapshot.SourceRef toSourceRef(SupplierScoreEventTrigger trigger) {
        PendingSnapshot.SourceRef ref = new PendingSnapshot.SourceRef();
        ref.setTriggerType(trigger.getTriggerType());
        ref.setSourceRefId(trigger.getSourceRefId());
        ref.setSourceRefNo(trigger.getSourceRefNo());
        ref.setOccurredAt(trigger.getOccurredAt());
        return ref;
    }

    /** 读取现有合并窗口；损坏 JSON 必须报错，避免覆盖尚未处理的来源。 */
    private PendingSnapshot readSnapshot(String key) {
        String json = stringRedisTemplate.opsForValue().get(key);
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, PendingSnapshot.class);
        } catch (JsonProcessingException e) {
            log.error("解析 pending JSON 失败 key={}", key, e);
            throw new IllegalStateException("pending JSON 损坏，不能覆盖现有事件", e);
        }
    }

    /** 保存完整合并窗口并设置一天 TTL，为延迟消费和重试保留 pending。 */
    private void writeSnapshot(String key, PendingSnapshot snapshot) {
        try {
            String json = objectMapper.writeValueAsString(snapshot);
            stringRedisTemplate.opsForValue().set(key, json,
                    Duration.ofMillis(SupplierScoreConstants.PENDING_TTL_MS));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("pending JSON 序列化失败", e);
        }
    }

    /**
     * 消息合并窗口快照，与数据库评分事实快照独立。
     */
    @Data
    public static class PendingSnapshot {
        /** 统一评分批次号，同时关联合并窗口、消息与变更日志；重投沿用，旧消息不能清理新窗口。 */
        private String batchNo;
        /** 首个事件进入窗口的 Unix 毫秒时间。 */
        private Long firstEventAt;
        /** 预计触发消费的 Unix 毫秒时间，用于识别久未消费的旧窗口。 */
        private Long scheduledFireAt;
        /** 来源事件条数，与 sourceRefs 的条数保持一致。 */
        private Integer mergedCount;
        /** 窗口内全部来源；重建旧窗口时继承其未处理来源。 */
        private List<SourceRef> sourceRefs;

        /** 单个业务事件的来源，不存储可重新查询的评分事实。 */
        @Data
        public static class SourceRef {
            /** 业务触发类型；Redis JSON 仍保存枚举名称，消费恢复时读取为同一枚举。 */
            private TriggerType triggerType;
            /** 来源业务主键，首次完全入库事件使用采购单 ID。 */
            private Long sourceRefId;
            /** 来源业务单号，供日志定位。 */
            private String sourceRefNo;
            /** 事件发生的 Unix 毫秒时间。 */
            private Long occurredAt;
        }
    }
}
