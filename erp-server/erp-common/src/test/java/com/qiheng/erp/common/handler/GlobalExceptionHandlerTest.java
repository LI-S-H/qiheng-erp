package com.qiheng.erp.common.handler;

import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 验证系统异常上报只记录真实服务端故障，SSE 客户端断开不进入异常中心。
 */
@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {

    @Mock
    private SystemExceptionMqPublisher publisher;

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler(publisher);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    /**
     * SSE 异步响应不可用是客户端断开的标准信号，不应上报。
     */
    @Test
    void asyncRequestNotUsableOnSseIsIgnored() {
        bindRequest("GET", "/erp/ai/assistant/conversations/1/events");

        handler.handleAsyncRequestNotUsable(new AsyncRequestNotUsableException("ServletOutputStream failed to flush"));

        verifyNoInteractions(publisher);
    }

    /**
     * SSE 事件流上的 broken pipe 常见于刷新和切换会话，按客户端断开处理。
     */
    @Test
    void brokenPipeOnSseIsIgnored() {
        bindRequest("GET", "/erp/ai/assistant/conversations/1/events");
        IOException error = new IOException("你的主机中的软件中止了一个已建立的连接");

        var result = handler.handleIOException(error);

        assertThat(result).isNull();
        verifyNoInteractions(publisher);
    }

    /**
     * 非 SSE 接口的 IO 异常仍需上报，避免掩盖真实的网络或存储故障。
     */
    @Test
    void brokenPipeOnNonSseIsReported() {
        bindRequest("GET", "/erp/ai/assistant/conversations/1/runtime");
        IOException error = new IOException("Broken pipe");

        var result = handler.handleIOException(error);

        assertThat(result).isNotNull();
        verify(publisher).publishSystemError(error);
    }

    /**
     * 普通未知异常继续上报。
     */
    @Test
    void genericExceptionIsReported() {
        bindRequest("POST", "/erp/sales/order");
        RuntimeException error = new RuntimeException("boom");

        var result = handler.handleException(error);

        assertThat(result).isNotNull();
        verify(publisher).publishSystemError(error);
    }

    private void bindRequest(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setServletPath(uri.replaceFirst("^/erp", ""));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}