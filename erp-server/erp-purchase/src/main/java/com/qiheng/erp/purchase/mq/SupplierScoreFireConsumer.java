package com.qiheng.erp.purchase.mq;

import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreRecalcContext;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreRecalcResult;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeSource;
import com.qiheng.erp.purchase.domain.supplierscore.enums.ScoreSourceBusinessType;
import com.qiheng.erp.purchase.domain.supplierscore.enums.OperatorType;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreFireMessage;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import com.qiheng.erp.purchase.service.support.PendingRedisSupport;
import com.qiheng.erp.purchase.service.support.ScoreRecalcPendingService;
import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Objects;

/**
 * SCHEDULED_FIRE Consumer。
 *
 * <p>消费由 {@link com.qiheng.erp.purchase.service.support.ScoreRecalcPendingService#mergeAndScheduleFire}
 * 投递的 5 分钟延迟消息。逻辑:</p>
 * <ol>
 *   <li>获取供应商锁，核对 Redis pending 的 batchNo</li>
 *   <li>匹配失败 → 已处理或新窗口接管，本次跳过并 ACK</li>
 *   <li>匹配成功 → 调 {@link SupplierScoreRecalculateService#recalcForSupplier(ScoreRecalcContext)}</li>
 *   <li>成功后比较删除 pending 并 ACK；失败保留 pending 让 MQ 重投</li>
 * </ol>
 *
 * @author Li
 * @since 2026-09-23
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "supplier-score.rocketmq.consumer.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${supplier-score.rocketmq.topic:erp-supplier-score-recalc}",
        selectorExpression = "SCHEDULED_FIRE",
        consumerGroup = "${supplier-score.rocketmq.consumer.group:erp-supplier-score-fire-consumer}"
)
public class SupplierScoreFireConsumer implements RocketMQListener<SupplierScoreFireMessage> {

    private final SupplierScoreRecalculateService recalculateService;
    private final PendingRedisSupport pendingRedisSupport;
    private final RedissonClient redissonClient;

    /**
     * 持供应商锁核对窗口并执行评分，评分事务提交成功后才清理 pending。
     * 无效或已处理消息直接返回；重算及 Redis 故障抛出异常供 MQ 重试。
     *
     * @param message 评分延迟触发消息，包含供应商 ID 和统一评分批次号
     */
    @Override
    public void onMessage(SupplierScoreFireMessage message) {
        if (message == null || message.getSupplierId() == null || message.getBatchNo() == null
                || message.getBatchNo().isBlank()) {
            log.warn("SCHEDULED_FIRE 消息必填字段为空,跳过");
            return;
        }
        Long supplierId = message.getSupplierId();
        String batchNo = message.getBatchNo();
        // 1. 获取供应商锁，核对 Redis pending 的 batchNo
        RLock lock = redissonClient.getLock(SupplierScoreRedisKeys.lockKey(supplierId));
        // 同一供应商的合并、评分和 pending 清理串行；租期交由 Redisson watchdog 续约。
        lock.lock();
        try {
            // 2. 核对 Redis pending 的 batchNo
            ScoreRecalcPendingService.PendingSnapshot snapshot =
                    pendingRedisSupport.findMatchingSnapshot(supplierId, batchNo);
            if (snapshot == null) {
                // 3. 匹配失败(batchNo 不匹配) → 已处理或新窗口接管，本次跳过并 ACK
                log.info("SCHEDULED_FIRE 批次已处理或被新窗口接管 supplierId={} batchNo={}",
                        supplierId, batchNo);
                return;
            }
            // 4. 匹配成功 → 构建评分上下文并调用评分服务
            ScoreRecalcContext ctx = ScoreRecalcContext.builder()
                .supplierId(supplierId)
                .batchNo(batchNo)
                .triggerType(TriggerType.INBOUND_TRIGGER)
                // 完整订单集合用于事实计算，日志来源独立保留全部对象，不取首单代替多单。
                .completedOrderIds(snapshot.getSourceRefs() == null ? List.of()
                        : snapshot.getSourceRefs().stream().map(ScoreRecalcPendingService.PendingSnapshot.SourceRef::getSourceRefId)
                                .filter(Objects::nonNull).distinct().toList())
                .relatedSources(snapshot.getSourceRefs() == null ? List.of()
                        : snapshot.getSourceRefs().stream().map(source -> new ScoreChangeSource(
                                ScoreSourceBusinessType.PURCHASE_ORDER,
                                source.getSourceRefId() == null ? null : source.getSourceRefId().toString(),
                                source.getSourceRefNo())).distinct().toList())
                .operatorType(OperatorType.SYSTEM.name())
                .operatorName("完全入库重算")
                .mergedReason("完全入库合并重算，共 " + (snapshot.getSourceRefs() == null ? 0
                        : snapshot.getSourceRefs().stream().map(ScoreRecalcPendingService.PendingSnapshot.SourceRef::getSourceRefId)
                                .filter(Objects::nonNull).distinct().count()) + " 张采购单")
                .executionMode("ASYNC")
                .build();
            // 5. 调用评分服务
            ScoreRecalcResult result = recalculateService.recalcForSupplier(ctx);
            // 评分事务成功后才删除 pending；失败时保留批次供 MQ 重投。
            pendingRedisSupport.compareAndDelete(supplierId, batchNo);
            log.info("SCHEDULED_FIRE 重算完成 supplierId={} changed={}",
                    supplierId, result != null && result.isChanged());
        } catch (Exception e) {
            log.error("SCHEDULED_FIRE 重算异常 supplierId={},让 MQ 重投", supplierId, e);
            throw e;
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

}
