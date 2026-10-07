package com.qiheng.erp.common.mq;

import com.qiheng.erp.common.constant.SystemExceptionConstants;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendCallback;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.Message;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验证系统异常消息发送组件：异步投递、Template 缺失跳过、发送失败吞异常与来源模块推断。
 *
 * <p>发布端采用 RocketMQ 原生异步（asyncSend），payload 直接传递对象由
 * RocketMQMessageConverter 统一序列化；单测断言捕获消息的 payload 对象字段与回调不外抛，
 * 真实 JSON 往返由闭环 IT 覆盖。</p>
 *
 * @author Li
 * @since 2026-10-07
 */
@ExtendWith(MockitoExtension.class)
class SystemExceptionMqPublisherTest {

    @Mock
    private ObjectProvider<SystemExceptionRocketMQTemplate> templateProvider;
    @Mock
    private SystemExceptionRocketMQTemplate template;
    @Mock
    private DefaultMQProducer producer;

    private SystemExceptionMqPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new SystemExceptionMqPublisher(templateProvider);
        ReflectionTestUtils.setField(publisher, "topic", "erp-system-exception-record");
    }

    private void stubTemplateAvailable() {
        lenient().when(templateProvider.getIfAvailable()).thenReturn(template);
        lenient().when(template.getProducer()).thenReturn(producer);
        lenient().when(producer.getSendMsgTimeout()).thenReturn(3000);
    }

    @Test
    void publishSendsPayloadAsynchronouslyWithTagAndKeysHeader() {
        stubTemplateAvailable();
        SystemExceptionRecordMessage message = new SystemExceptionRecordMessage();
        message.setExceptionType(SystemExceptionConstants.TYPE_SYSTEM_ERROR);
        message.setSourceModule("SALES");
        message.setSourceNo("POST /sales/order");
        message.setSeverity(SystemExceptionConstants.SEVERITY_HIGH);
        message.setErrorCode("99999");
        message.setErrorMessage("NPE");
        message.setDetailSummary("销售模块接口 POST /sales/order 抛出 NullPointerException");
        message.setOccurredAt(1728000000000L);

        publisher.publish(message);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<SystemExceptionRecordMessage>> payloadCaptor =
                ArgumentCaptor.forClass(Message.class);
        ArgumentCaptor<SendCallback> callbackCaptor = ArgumentCaptor.forClass(SendCallback.class);
        verify(template).asyncSend(anyString(), payloadCaptor.capture(), callbackCaptor.capture(), anyLong());
        Message<SystemExceptionRecordMessage> sent = payloadCaptor.getValue();
        assertThat(sent.getHeaders().get(RocketMQHeaders.KEYS)).isEqualTo("POST /sales/order");
        assertThat(sent.getPayload()).isSameAs(message);
        verify(template).asyncSend(
                eq("erp-system-exception-record:SYSTEM_ERROR"),
                any(Message.class), any(SendCallback.class), anyLong());
        // 回调成功路径不外抛
        assertThatCode(() -> callbackCaptor.getValue().onSuccess(new SendResult()))
                .doesNotThrowAnyException();
    }

    @Test
    void publishSkipsSilentlyWhenTemplateMissing() {
        when(templateProvider.getIfAvailable()).thenReturn(null);
        SystemExceptionRecordMessage message = new SystemExceptionRecordMessage();
        message.setExceptionType(SystemExceptionConstants.TYPE_JOB_FAILED);

        assertThatCode(() -> publisher.publish(message)).doesNotThrowAnyException();
        verify(template, never()).asyncSend(anyString(), any(Message.class), any(SendCallback.class), anyLong());
    }

    @Test
    void publishSwallowsSubmitFailure() {
        stubTemplateAvailable();
        doThrow(new RuntimeException("MQ 不可用")).when(template)
                .asyncSend(anyString(), any(Message.class), any(SendCallback.class), anyLong());
        SystemExceptionRecordMessage message = new SystemExceptionRecordMessage();
        message.setExceptionType(SystemExceptionConstants.TYPE_JOB_FAILED);
        message.setSourceModule("SCHEDULER");

        // 提交异步发送本身失败（如客户端在途消息超限）只记日志，绝不二次抛出
        assertThatCode(() -> publisher.publish(message)).doesNotThrowAnyException();
    }

    @Test
    void asyncCallbackOnExceptionDoesNotThrow() {
        stubTemplateAvailable();
        publisher.publish(validJobMessage());

        ArgumentCaptor<SendCallback> callbackCaptor = ArgumentCaptor.forClass(SendCallback.class);
        verify(template).asyncSend(anyString(), any(Message.class), callbackCaptor.capture(), anyLong());

        // 异步重试耗尽后回调的失败路径只记日志，不向 MQ 客户端线程抛出
        assertThatCode(() -> callbackCaptor.getValue().onException(new RuntimeException("重试耗尽")))
                .doesNotThrowAnyException();
    }

    @Test
    void publishSkipsMessageWithoutType() {
        stubTemplateAvailable();
        publisher.publish(new SystemExceptionRecordMessage());
        verify(template, never()).asyncSend(anyString(), any(Message.class), any(SendCallback.class), anyLong());
    }

    @Test
    void publishSystemErrorResolvesModuleFromRequestUri() {
        stubTemplateAvailable();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/erp/sales/order");
        // 模拟真实容器：servletPath 不含 context path，来源推断必须基于 servletPath
        request.setServletPath("/sales/order");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            publisher.publishSystemError(new RuntimeException());

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Message<SystemExceptionRecordMessage>> captor =
                    ArgumentCaptor.forClass(Message.class);
            verify(template).asyncSend(anyString(), captor.capture(), any(SendCallback.class), anyLong());
            SystemExceptionRecordMessage sent = captor.getValue().getPayload();
            // servletPath 首段 sales 推断为 SALES，sourceNo 记录"方法 URI"
            assertThat(sent.getSourceModule()).isEqualTo("SALES");
            assertThat(sent.getSourceNo()).isEqualTo("POST /erp/sales/order");
            assertThat(sent.getSeverity()).isEqualTo(SystemExceptionConstants.SEVERITY_HIGH);
            // errorCode 取异常类简单名：让唯一键 (source_module, source_no, error_code, occurred_at)
            // 能区分同一来源同一秒内的不同异常（如 NPE vs SQL），而非全部归为 "99999" 漏掉一条
            assertThat(sent.getErrorCode()).isEqualTo("RuntimeException");
            assertThat(sent.getExceptionType()).isEqualTo(SystemExceptionConstants.TYPE_SYSTEM_ERROR);
            assertThat(sent.getErrorMessage()).isNotBlank();
            assertThat(sent.getDetailSummary()).isNotBlank();
            assertThat(sent.getOccurredAt()).isPositive();
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void publishSystemErrorFallsBackToSystemModuleWithoutRequest() {
        stubTemplateAvailable();
        RequestContextHolder.resetRequestAttributes();
        // 无消息异常：errorMessage 为 null 时必须用异常类名兜底（表字段 NOT NULL）
        publisher.publishSystemError(new RuntimeException());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Message<SystemExceptionRecordMessage>> captor =
                ArgumentCaptor.forClass(Message.class);
        verify(template).asyncSend(anyString(), captor.capture(), any(SendCallback.class), anyLong());
        SystemExceptionRecordMessage sent = captor.getValue().getPayload();
        // 非 HTTP 线程兜底 SYSTEM
        assertThat(sent.getSourceModule()).isEqualTo("SYSTEM");
        assertThat(sent.getErrorMessage()).isEqualTo("java.lang.RuntimeException");
    }

    private SystemExceptionRecordMessage validJobMessage() {
        SystemExceptionRecordMessage message = new SystemExceptionRecordMessage();
        message.setExceptionType(SystemExceptionConstants.TYPE_JOB_FAILED);
        message.setSourceModule(SystemExceptionConstants.MODULE_SCHEDULER);
        message.setSourceNo("dashboard-daily-snapshot");
        message.setSeverity(SystemExceptionConstants.SEVERITY_HIGH);
        message.setErrorCode("JOB_EXECUTION_FAILED");
        message.setErrorMessage("任务失败");
        message.setDetailSummary("任务失败");
        message.setOccurredAt(1728000000000L);
        return message;
    }
}
