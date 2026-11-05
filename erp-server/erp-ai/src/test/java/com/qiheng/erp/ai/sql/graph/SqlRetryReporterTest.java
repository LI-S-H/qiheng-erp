package com.qiheng.erp.ai.sql.graph;

import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import com.qiheng.erp.common.mq.SystemExceptionRocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SqlRetryReporterTest {
    @Test
    void retryExhaustionReportsOncePerRunAndKeepsFeedbackPrivate() {
        var publisher = mock(SystemExceptionMqPublisher.class);
        var reporter = new SqlRetryReporter(publisher);
        var support = new SqlNodeSupport(null, null, null, 2, reporter);
        var work = new HashMap<String, Object>(Map.of("sqlRunId", "run1", "attempt", 2));

        var result = support.retry(work, "包含业务数据的 SQL", "查询失败", "guard");
        support.retryExhausted(work, "查询失败");

        var messages = ArgumentCaptor.forClass(SystemExceptionRecordMessage.class);
        verify(publisher, times(2)).publish(messages.capture());
        assertThat(messages.getAllValues()).extracting(SystemExceptionRecordMessage::getEventId).containsOnly(messages.getValue().getEventId());
        assertThat(messages.getValue().getDetailSummary()).contains("run1", "guard", "2").doesNotContain("业务数据");
        assertThat(result).containsEntry("code", "SQL_RETRY_EXHAUSTED").containsEntry("route", "end");
    }

    @Test
    void publishingFailureDoesNotChangeToolFailure() {
        var publisher = mock(SystemExceptionMqPublisher.class);
        doThrow(new IllegalStateException("unavailable")).when(publisher).publish(any());
        var support = new SqlNodeSupport(null, null, null, 1, new SqlRetryReporter(publisher));
        var work = new HashMap<String, Object>(Map.of("sqlRunId", "run2", "attempt", 1));
        assertThat(support.retry(work, "反馈", "查询失败", "execute"))
                .containsEntry("code", "SQL_RETRY_EXHAUSTED").containsEntry("route", "end");
    }

    @Test
    void inspectionExhaustionReportsFieldLevelReason() {
        var publisher = mock(SystemExceptionMqPublisher.class);
        var support = new SqlNodeSupport(null, null, null, 1, new SqlRetryReporter(publisher));
        var work = new HashMap<String, Object>(Map.of("sqlRunId", "run3", "attempt", 1,
                "retryDiagnostic", "数据说明的字段序号或名称与查询结果不一致：第 1 列"));
        support.retry(work, "检查失败", "查询失败", "inspect");
        var saved = ArgumentCaptor.forClass(SystemExceptionRecordMessage.class);
        verify(publisher).publish(saved.capture());
        assertThat(saved.getValue().getDetailSummary()).contains("阶段=inspect", "字段序号", "第 1 列");
    }

    /** 使用真实发布器验证事件 ID 能通过协议校验。 */
    @Test
    void eventIdPassesRealPublisherValidation() {
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemExceptionRocketMQTemplate> provider = mock(ObjectProvider.class);
        var reporter = new SqlRetryReporter(new SystemExceptionMqPublisher(provider));
        reporter.report("sqlrun_123", "guard", 5);
        verify(provider).getIfAvailable();
    }
}
