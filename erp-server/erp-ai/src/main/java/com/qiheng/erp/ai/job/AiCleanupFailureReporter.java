package com.qiheng.erp.ai.job;

import com.qiheng.erp.common.constant.SystemExceptionConstants;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** 异步上报过期文件清理失败，MQ 不可用时保留日志。 */
@Component
public class AiCleanupFailureReporter {
    private static final Logger log = LoggerFactory.getLogger(AiCleanupFailureReporter.class);
    private final SystemExceptionMqPublisher publisher;

    public AiCleanupFailureReporter(ObjectProvider<SystemExceptionMqPublisher> provider) {
        this.publisher = provider.getIfAvailable();
    }

    /** 上报受管目录相对路径和失败阶段，不让 MQ 异常中断清理。 */
    public void report(String type, String id, String relativePath, String stage, Exception error) {
        log.warn("AI 过期数据清理失败 type={} id={} file={} stage={}", type, id, relativePath, stage, error);
        if (publisher == null) {
            return;
        }
        try {
            var message = new SystemExceptionRecordMessage();
            long eventId = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
            message.setEventId(Long.toString(eventId == 0 ? 1 : eventId));
            message.setExceptionType("AI_RETENTION_CLEANUP_FAILED");
            message.setSourceModule(SystemExceptionConstants.MODULE_AI);
            message.setSourceNo("AI_RETENTION/" + id);
            message.setSeverity(SystemExceptionConstants.SEVERITY_MEDIUM);
            message.setErrorCode("AI_RETENTION_CLEANUP_FAILED");
            message.setErrorMessage("AI 过期数据清理失败");
            String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            reason = reason.replace('\n', ' ').replace('\r', ' ');
            message.setDetailSummary("类型=" + type + " 文件=" + relativePath + " 阶段=" + stage
                    + " 原因=" + reason.substring(0, Math.min(reason.length(), 160)));
            message.setResolveHint("检查文件与数据库状态后重试清理。");
            message.setOccurredAt(System.currentTimeMillis());
            publisher.publish(message);
        } catch (RuntimeException publishError) {
            log.warn("AI 过期清理异常上报失败 id={}", id, publishError);
        }
    }
}
