package com.qiheng.erp.ai.job;

import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiCleanupFailureReporterTest {
    @Test
    void sendsFileAndFailureStageWithoutBlockingCleanup() {
        var publisher = mock(SystemExceptionMqPublisher.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemExceptionMqPublisher> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(publisher);
        var reporter = new AiCleanupFailureReporter(provider);
        reporter.report("SQL 数据集", "ds_1", "ds_1.jsonl", "删除文件", new IllegalStateException("拒绝访问"));
        var message = ArgumentCaptor.forClass(SystemExceptionRecordMessage.class);
        verify(publisher).publish(message.capture());
        assertThat(message.getValue().getDetailSummary()).contains("ds_1.jsonl", "删除文件", "拒绝访问");
        doThrow(new IllegalStateException("MQ unavailable")).when(publisher).publish(any());
        assertThatCode(() -> reporter.report("SQL 数据集", "ds_1", "ds_1.jsonl", "删除文件",
                new IllegalStateException("拒绝访问"))).doesNotThrowAnyException();
    }
}
