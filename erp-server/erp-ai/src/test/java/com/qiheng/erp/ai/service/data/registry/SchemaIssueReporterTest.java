package com.qiheng.erp.ai.service.data.registry;

import com.qiheng.erp.common.constant.SystemExceptionConstants;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 元数据异常上报单测：字段正确、只报新增、超长收敛。
 *
 * @author Li
 * @since 2026-10-21
 */
class SchemaIssueReporterTest {

    private final SystemExceptionMqPublisher publisher = mock(SystemExceptionMqPublisher.class);

    private SchemaIssueReporter reporter() {
        return new SchemaIssueReporter(publisher);
    }

    @Test
    void invalidRelationIsReportedWithExpectedFields() {
        reporter().reportInvalidRelations(List.of("purchase_order_item.purchase_order_id=purchase_order.id"));

        ArgumentCaptor<SystemExceptionRecordMessage> captor =
                ArgumentCaptor.forClass(SystemExceptionRecordMessage.class);
        verify(publisher).publish(captor.capture());
        SystemExceptionRecordMessage message = captor.getValue();
        assertThat(message.getExceptionType())
                .isEqualTo(SystemExceptionConstants.TYPE_DATA_SCHEMA_INVALID);
        assertThat(message.getSourceModule()).isEqualTo(SystemExceptionConstants.MODULE_AI);
        assertThat(message.getSeverity()).isEqualTo(SystemExceptionConstants.SEVERITY_HIGH);
        assertThat(message.getErrorCode())
                .isEqualTo(SystemExceptionConstants.CODE_DATA_RELATION_INVALID);
        assertThat(message.getErrorMessage()).contains("purchase_order_item.purchase_order_id=purchase_order.id");
        assertThat(message.getResolveHint()).contains("ai_data_table_relation");
        assertThat(message.getOccurredAt()).isNotNull();
        assertThat(message.getEventId()).isNotBlank();
    }

    @Test
    void sameRelationIsNotReportedTwice() {
        SchemaIssueReporter reporter = reporter();
        reporter.reportInvalidRelations(List.of("a.b=c.d"));
        reporter.reportInvalidRelations(List.of("a.b=c.d"));

        verify(publisher, times(1)).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void onlyNewRelationIsReported() {
        SchemaIssueReporter reporter = reporter();
        reporter.reportInvalidRelations(List.of("a.b=c.d"));
        reporter.reportInvalidRelations(List.of("a.b=c.d", "e.f=g.h"));

        ArgumentCaptor<SystemExceptionRecordMessage> captor =
                ArgumentCaptor.forClass(SystemExceptionRecordMessage.class);
        verify(publisher, times(2)).publish(captor.capture());
        // 第二条消息只应包含新增的那条
        assertThat(captor.getAllValues().get(1).getErrorMessage()).contains("e.f=g.h");
    }

    @Test
    void emptyRelationListReportsNothing() {
        reporter().reportInvalidRelations(List.of());
        reporter().reportInvalidRelations(null);

        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void longRelationListIsTruncatedToFitColumnLength() {
        List<String> relations = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            relations.add("table_with_long_name_" + i + ".column_name_" + i + "=target_table.column_name");
        }

        reporter().reportInvalidRelations(relations);

        ArgumentCaptor<SystemExceptionRecordMessage> captor =
                ArgumentCaptor.forClass(SystemExceptionRecordMessage.class);
        verify(publisher).publish(captor.capture());
        SystemExceptionRecordMessage message = captor.getValue();
        // system_exception 的 error_message / detail_summary 都是 VARCHAR(1000)，超长会 insert 失败
        assertThat(message.getErrorMessage().length()).isLessThanOrEqualTo(1000);
        assertThat(message.getDetailSummary().length()).isLessThanOrEqualTo(1000);
        assertThat(message.getErrorMessage()).contains("共 200 条");
    }
}
