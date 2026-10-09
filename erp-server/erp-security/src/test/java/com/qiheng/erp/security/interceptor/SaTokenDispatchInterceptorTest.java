package com.qiheng.erp.security.interceptor;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.SaTokenContextException;
import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.security.config.SaTokenConfig;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SaTokenDispatchInterceptorTest {
    private final SaTokenDispatchInterceptor interceptor = new SaTokenDispatchInterceptor();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @Test
    void successfulRequestCanFinishOnAsyncDispatchWithoutSaTokenContext() throws Exception {
        MockHttpServletRequest request = servletRequest("/ai/assistant/messages/stream", DispatcherType.REQUEST);
        try (var login = mockStatic(StpUtil.class)) {
            assertTrue(interceptor.preHandle(request, response, new Object()));
            login.verify(StpUtil::checkLogin, times(1));
            request.setDispatcherType(DispatcherType.ASYNC);
            login.when(StpUtil::checkLogin).thenThrow(new SaTokenContextException("异步线程不存在上下文"));
            assertTrue(interceptor.preHandle(request, response, new Object()));
            login.verify(StpUtil::checkLogin, times(1));
        }
    }

    @Test
    void unauthenticatedRequestNeverGetsTrustedAsyncMarker() throws Exception {
        MockHttpServletRequest request = servletRequest("/ai/assistant/messages/stream", DispatcherType.REQUEST);
        try (var login = mockStatic(StpUtil.class)) {
            login.when(StpUtil::checkLogin).thenThrow(notLoggedIn());
            assertThrows(NotLoginException.class, () -> interceptor.preHandle(request, response, new Object()));
            request.setDispatcherType(DispatcherType.ASYNC);
            assertThrows(NotLoginException.class, () -> interceptor.preHandle(request, response, new Object()));
            login.verify(StpUtil::checkLogin, times(1));
        }
    }

    @Test
    void unmarkedAsyncDispatchCannotEnterBusinessEndpoint() {
        MockHttpServletRequest request = servletRequest("/ai/assistant/messages/stream", DispatcherType.ASYNC);
        try (var login = mockStatic(StpUtil.class)) {
            assertThrows(NotLoginException.class, () -> interceptor.preHandle(request, response, new Object()));
            login.verifyNoInteractions();
        }
    }

    @Test
    void requestAlwaysRechecksLoginEvenWhenSameRequestPreviouslyPassed() throws Exception {
        MockHttpServletRequest request = servletRequest("/ai/assistant/messages/stream", DispatcherType.REQUEST);
        try (var login = mockStatic(StpUtil.class)) {
            assertTrue(interceptor.preHandle(request, response, new Object()));
            login.when(StpUtil::checkLogin).thenThrow(notLoggedIn());
            assertThrows(NotLoginException.class, () -> interceptor.preHandle(request, response, new Object()));
            login.verify(StpUtil::checkLogin, times(2));
            request.setDispatcherType(DispatcherType.ASYNC);
            assertThrows(NotLoginException.class, () -> interceptor.preHandle(request, response, new Object()));
        }
    }

    @Test
    void onlyRealErrorPageDispatchSkipsAuthentication() throws Exception {
        try (var login = mockStatic(StpUtil.class)) {
            assertTrue(interceptor.preHandle(servletRequest("/error", DispatcherType.ERROR), response, new Object()));
            login.verifyNoInteractions();
            assertThrows(NotLoginException.class, () -> interceptor.preHandle(
                    servletRequest("/ai/assistant/messages/stream", DispatcherType.ERROR), response, new Object()));
            login.when(StpUtil::checkLogin).thenThrow(notLoggedIn());
            assertThrows(NotLoginException.class, () -> interceptor.preHandle(
                    servletRequest("/error", DispatcherType.REQUEST), response, new Object()));
            login.verify(StpUtil::checkLogin, times(1));
        }
    }

    @Test
    void actualMvcConfigurationAuthenticatesOnceAcrossAsyncDispatch() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(MvcConfig.class, SaTokenConfig.class);
            context.refresh();
            MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).build();
            try (var login = mockStatic(StpUtil.class)) {
                var result = mvc.perform(get("/protected/async"))
                        .andExpect(request().asyncStarted()).andReturn();
                login.verify(StpUtil::checkLogin, times(1));
                // 模拟Sa-Token过滤器仅包住初次REQUEST，后续ASYNC调度没有请求上下文。
                login.when(StpUtil::checkLogin).thenThrow(new SaTokenContextException("没有请求上下文"));
                mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andExpect(content().string("done"));
                login.verify(StpUtil::checkLogin, times(1));
                mvc.perform(get("/auth/login")).andExpect(status().isOk());
                login.verify(StpUtil::checkLogin, times(1));
            }
        }
    }

    private MockHttpServletRequest servletRequest(String uri, DispatcherType type) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri); request.setDispatcherType(type);
        return request;
    }

    private static NotLoginException notLoggedIn() {
        return NotLoginException.newInstance("login", NotLoginException.NOT_TOKEN, "未登录", null);
    }

    @Configuration
    @EnableWebMvc
    static class MvcConfig {
        @Bean AsyncController asyncController() { return new AsyncController(); }
    }

    @RestController
    static class AsyncController {
        @GetMapping("/protected/async") Callable<String> async() { return () -> "done"; }
        @GetMapping("/auth/login") String login() { return "登录入口"; }
    }
}
