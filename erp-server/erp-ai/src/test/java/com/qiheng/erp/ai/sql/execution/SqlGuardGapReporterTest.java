package com.qiheng.erp.ai.sql.execution;

import com.qiheng.erp.common.constant.SystemExceptionConstants;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 守卫缺口上报的契约：消息字段、去重、截断、失败不影响调用方。
 *
 * @author Li
 * @since 2026-10-26
 */
class SqlGuardGapReporterTest {

    private final SystemExceptionMqPublisher publisher = mock(SystemExceptionMqPublisher.class);

    private final SqlGuardGapReporter reporter = new SqlGuardGapReporter(publisher);

    @Test
    void reportsExpectedMessageFields() {
        reporter.reportWallRejected("run-1", "step-1", "abcdef1234567890",
                "sql injection violation, comment not allow : SELECT 1 -- 注释");

        SystemExceptionRecordMessage message = captureSingle();
        assertThat(message.getEventId()).isNotBlank();
        assertThat(message.getExceptionType()).isEqualTo(SystemExceptionConstants.TYPE_SQL_GUARD_GAP);
        assertThat(message.getSourceModule()).isEqualTo(SystemExceptionConstants.MODULE_AI);
        assertThat(message.getSourceNo()).isEqualTo("AI_SQL_GUARD/run-1");
        assertThat(message.getSeverity()).isEqualTo(SystemExceptionConstants.SEVERITY_HIGH);
        assertThat(message.getErrorCode()).isEqualTo(SystemExceptionConstants.CODE_SQL_GUARD_GAP);
        assertThat(message.getErrorMessage()).contains("comment not allow");
        assertThat(message.getDetailSummary()).contains("sqlHash=abcdef12");
        assertThat(message.getResolveHint()).isNotBlank();
        assertThat(message.getOccurredAt()).isPositive();
    }

    @Test
    void detailSummaryDoesNotLeakSqlText() {
        reporter.reportWallRejected("run-1", "step-1", "abcdef1234567890",
                "sql injection violation : SELECT supplier_name FROM purchase_order");

        SystemExceptionRecordMessage message = captureSingle();
        assertThat(message.getDetailSummary()).doesNotContain("supplier_name");
        assertThat(message.getErrorMessage()).contains("SELECT supplier_name");
    }

    @Test
    void sameSqlIsReportedOnlyOnceWithinWindow() {
        reporter.reportWallRejected("run-1", "step-1", "same-hash", "violation");
        reporter.reportWallRejected("run-2", "step-2", "same-hash", "violation");

        verify(publisher, times(1)).publish(any());
    }

    @Test
    void differentSqlIsReportedSeparately() {
        reporter.reportWallRejected("run-1", "step-1", "hash-a", "violation a");
        reporter.reportWallRejected("run-1", "step-1", "hash-b", "violation b");

        verify(publisher, times(2)).publish(any());
    }

    @Test
    void longMessageIsTruncatedToColumnLimit() {
        String longSql = "SELECT ".repeat(2000);

        reporter.reportWallRejected("run-1", "step-1", "hash-long", longSql);

        SystemExceptionRecordMessage message = captureSingle();
        assertThat(message.getErrorMessage().length()).isLessThanOrEqualTo(1000);
        assertThat(message.getDetailSummary().length()).isLessThanOrEqualTo(1000);
    }

    @Test
    void publisherFailureDoesNotPropagate() {
        doThrow(new RuntimeException("mq down")).when(publisher).publish(any());

        assertThatCode(() -> reporter.reportWallRejected("run-1", "step-1", "hash-x", "violation"))
                .doesNotThrowAnyException();
    }

    private SystemExceptionRecordMessage captureSingle() {
        ArgumentCaptor<SystemExceptionRecordMessage> captor =
                ArgumentCaptor.forClass(SystemExceptionRecordMessage.class);
        verify(publisher).publish(captor.capture());
        return captor.getValue();
    }
}