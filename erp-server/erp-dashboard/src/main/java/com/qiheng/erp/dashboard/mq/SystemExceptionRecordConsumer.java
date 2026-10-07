package com.qiheng.erp.dashboard.mq;

import com.qiheng.erp.common.constant.SystemExceptionConstants;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.dashboard.domain.exception.entity.SystemException;
import com.qiheng.erp.dashboard.domain.exception.enums.SystemExceptionStatus;
import com.qiheng.erp.dashboard.mapper.SystemExceptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 系统异常记录 Consumer，system_exception 表的唯一写入口。
 *
 * <p>消费各来源（全局异常拦截器、定时任务、未来的死信监听与 AI 模块）投递的
 * {@link SystemExceptionRecordMessage}，生成 SE 前缀异常编号后入库。
 * 工作台 SYSTEM_EXCEPTION 待办由 DashboardSystemExceptionLoader 读取，无需额外处理。</p>
 *
 * <p>topic 为系统异常记录专用，tag 即异常类型，本 Consumer 有意订阅全部 tag（默认 *），
 * 新增异常类型无需修改此处。</p>
 *
 * <p>失败语义：消息字段缺失等数据问题直接 ACK 跳过（重投也不会成功）；
 * 数据库异常向外抛出让 MQ 重投。</p>
 *
 * @author Li
 * @since 2026-10-07
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "rocketmq", name = "name-server")
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = "${system-exception.rocketmq.topic:erp-system-exception-record}",
        consumerGroup = "${system-exception.rocketmq.consumer.group:erp-system-exception-consumer}"
)
public class SystemExceptionRecordConsumer implements RocketMQListener<SystemExceptionRecordMessage> {

    /** 业务时区：与 SupplierScoreScheduledJob 一致，避免部署环境 JVM 默认时区漂移导致 occurred_at 不一致 */
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private final SystemExceptionMapper systemExceptionMapper;
    private final BillNoGenerator billNoGenerator;

    /**
     * 消费异常记录消息：必填校验、四字段弱幂等查重后入库。
     * 数据问题 ACK 跳过，数据库异常抛出供 MQ 重投。
     *
     * @param message 异常记录消息
     */
    @Override
    public void onMessage(SystemExceptionRecordMessage message) {
        // 1. null 与必填校验：error_message / detail_summary 为表上 NOT NULL 字段
        if (message == null || message.getExceptionType() == null
                || message.getSourceModule() == null
                || message.getErrorMessage() == null || message.getErrorMessage().isBlank()
                || message.getDetailSummary() == null || message.getDetailSummary().isBlank()
                || message.getOccurredAt() == null) {
            log.warn("系统异常消息必填字段缺失，ACK 跳过：{}", message);
            return;
        }
        // 2. 毫秒时间戳转 LocalDateTime（固定业务时区，截断到秒）
        LocalDateTime occurredAt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(message.getOccurredAt()), BUSINESS_ZONE).withNano(0);
        // 3. 生成异常编号并入库，status 固定 PENDING 由工作台待办消费；
        //    幂等由唯一键 uk_system_exception_dedup(source_module, source_no, error_code, occurred_at) 强保证
        SystemException entity = new SystemException()
                .setExceptionNo(billNoGenerator.nextNo(SystemExceptionConstants.EXCEPTION_NO_PREFIX,
                        systemExceptionMapper::findMaxExceptionNoSequence))
                .setExceptionType(message.getExceptionType())
                .setSourceModule(message.getSourceModule())
                .setSourceNo(message.getSourceNo())
                .setSeverity(normalizeSeverity(message.getSeverity()))
                .setErrorCode(message.getErrorCode())
                .setErrorMessage(message.getErrorMessage())
                .setDetailSummary(message.getDetailSummary())
                .setResolveHint(message.getResolveHint())
                .setStatus(SystemExceptionStatus.PENDING.name())
                .setOccurredAt(occurredAt);
        try {
            systemExceptionMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            // 重复消息（MQ 重投等）：唯一键判重直接 ACK，不再新增记录；宁可少记不错记
            log.info("系统异常消息重复，ACK 跳过 type={} sourceNo={}",
                    message.getExceptionType(), message.getSourceNo());
            return;
        }
        log.info("系统异常已入库 exceptionNo={} type={} sourceNo={}",
                entity.getExceptionNo(), entity.getExceptionType(), entity.getSourceNo());
    }

    /** 严重级别归一化：HIGH/LOW 之外一律兜底 MEDIUM，避免脏值污染工作台优先级计算。 */
    private String normalizeSeverity(String severity) {
        if (SystemExceptionConstants.SEVERITY_HIGH.equals(severity)
                || SystemExceptionConstants.SEVERITY_LOW.equals(severity)) {
            return severity;
        }
        return SystemExceptionConstants.SEVERITY_MEDIUM;
    }
}