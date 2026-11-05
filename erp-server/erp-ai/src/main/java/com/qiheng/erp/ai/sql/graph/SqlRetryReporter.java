package com.qiheng.erp.ai.sql.graph;

import com.qiheng.erp.common.constant.SystemExceptionConstants;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** SQL 重试耗尽时发送可定位的系统异常，不记录模型输入或 SQL。 */
@Component
public class SqlRetryReporter {
    private static final Logger log = LoggerFactory.getLogger(SqlRetryReporter.class);
    private final SystemExceptionMqPublisher publisher;

    public SqlRetryReporter(SystemExceptionMqPublisher publisher) {
        this.publisher = publisher;
    }

    /** 同一次运行恢复重放时沿用事件 ID，由消费端去重。 */
    public void report(String runId, String stage, int attempts) {
        report(runId, stage, attempts, null);
    }

    /** 仅附加由结果检查节点生成的字段级诊断，不附带模型原文。 */
    public void report(String runId, String stage, int attempts, String diagnostic) {
        try {
            var message = new SystemExceptionRecordMessage();
            long eventId = UUID.nameUUIDFromBytes(("sql-retry:" + runId).getBytes(StandardCharsets.UTF_8))
                    .getMostSignificantBits() & Long.MAX_VALUE;
            message.setEventId(Long.toString(eventId == 0 ? 1 : eventId));
            message.setExceptionType("SQL_RETRY_EXHAUSTED");
            message.setSourceModule(SystemExceptionConstants.MODULE_AI);
            message.setSourceNo("AI_SQL/" + runId);
            message.setSeverity(SystemExceptionConstants.SEVERITY_MEDIUM);
            message.setErrorCode("SQL_RETRY_EXHAUSTED");
            message.setErrorMessage("SQL 取数重试次数已耗尽，未能完成查询。");
            String detail = "runId=" + runId + " 阶段=" + stage + " 尝试次数=" + attempts;
            if (diagnostic != null && !diagnostic.isBlank()) {
                detail += " 原因=" + diagnostic.substring(0, Math.min(diagnostic.length(), 120));
            }
            message.setDetailSummary(detail);
            message.setResolveHint("检查对应阶段的模型输出、授权 Schema 和 SQL 规则。");
            message.setOccurredAt(System.currentTimeMillis());
            publisher.publish(message);
        } catch (RuntimeException error) {
            log.warn("SQL 重试耗尽上报失败 runId={}", runId, error);
        }
    }
}
