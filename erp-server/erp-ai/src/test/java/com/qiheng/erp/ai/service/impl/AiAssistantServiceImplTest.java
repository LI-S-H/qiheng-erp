package com.qiheng.erp.ai.service.impl;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.qiheng.erp.ai.domain.dto.AiChatRequest;
import com.qiheng.erp.ai.domain.dto.AiMessageCreateCommand;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.vo.AiChatMessageVO;
import com.qiheng.erp.ai.service.IAiConversationService;
import com.qiheng.erp.ai.service.IAiMessageService;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.security.context.UserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiAssistantServiceImplTest {
    private ReactAgent agent;
    private IAiMessageService messages;
    private IAiConversationService conversations;
    private ObjectProvider<ReactAgent> provider;
    private AiAssistantServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        agent = mock(ReactAgent.class);
        messages = mock(IAiMessageService.class);
        conversations = mock(IAiConversationService.class);
        provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(agent);
        when(conversations.getOwnedConversation(100L, 7L)).thenReturn(new AiConversation().setId(100L).setUserId(7L));
        when(messages.recentForUser(100L, 7L)).thenReturn(List.of());
        when(messages.appendForUser(eq(100L), eq(7L), any())).thenAnswer(invocation -> {
            AiMessageCreateCommand command = invocation.getArgument(2);
            AiChatMessageVO result = new AiChatMessageVO();
            result.setMessageId(20L);
            result.setRole(command.getRole());
            result.setContent(command.getContent());
            return result;
        });
        service = new AiAssistantServiceImpl(provider, conversations, messages, 300);
    }

    @Test
    void bothProtocolsReturn503WithoutModelAndWriteNothing() {
        when(provider.getIfAvailable()).thenReturn(null);
        try (var context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode(),
                    assertThrows(BizException.class, () -> service.send(request())).getCode());
            assertEquals(ErrorCode.AI_SERVICE_UNAVAILABLE.getCode(),
                    assertThrows(BizException.class, () -> service.stream(request())).getCode());
            verify(messages, never()).appendForUser(anyLong(), anyLong(), any());
        }
    }

    @Test
    void foreignConversationDoesNotReadHistoryOrInvokeModel() {
        when(conversations.getOwnedConversation(100L, 7L)).thenThrow(new BizException(ErrorCode.AI_CONVERSATION_NOT_FOUND));
        try (var context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            assertEquals(ErrorCode.AI_CONVERSATION_NOT_FOUND.getCode(),
                    assertThrows(BizException.class, () -> service.stream(request())).getCode());
            verify(messages, never()).recentForUser(anyLong(), anyLong());
            verifyNoInteractions(agent);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void jsonReplaysDatabaseHistoryAndAddsCurrentUserOnce() throws Exception {
        AiChatMessageVO previousUser = new AiChatMessageVO();
        previousUser.setRole("user"); previousUser.setContent("旧问题");
        AiChatMessageVO previousAssistant = new AiChatMessageVO();
        previousAssistant.setRole("assistant"); previousAssistant.setContent("旧回答");
        when(messages.recentForUser(100L, 7L)).thenReturn(List.of(previousUser, previousAssistant));
        AtomicReference<List<Message>> input = new AtomicReference<>();
        when(agent.streamMessages(anyList(), any(RunnableConfig.class))).thenAnswer(invocation -> {
            input.set(invocation.getArgument(0));
            RunnableConfig config = invocation.getArgument(1);
            assertEquals("7:100", config.threadId().orElseThrow());
            return Flux.just(new AssistantMessage("你"), new AssistantMessage(" "), new AssistantMessage("好"));
        });
        try (var context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            assertEquals("你 好", service.send(request()).getMessage().getContent());
        }
        assertEquals(List.of("旧问题", "旧回答", "新问题"), input.get().stream().map(Message::getText).toList());
        var order = inOrder(messages, agent);
        order.verify(messages).recentForUser(100L, 7L);
        order.verify(messages).appendForUser(eq(100L), eq(7L), argThat(command -> "user".equals(command.getRole())));
        order.verify(agent).streamMessages(anyList(), any(RunnableConfig.class));
        order.verify(messages).appendForUser(eq(100L), eq(7L), argThat(command -> "assistant".equals(command.getRole())));
    }

    @Test
    void sseCompletionPersistsWithCapturedIdentityThenReturnsFinal() throws Exception {
        when(agent.streamMessages(anyList(), any(RunnableConfig.class)))
                .thenReturn(Flux.just(new AssistantMessage("真实回复")));
        SseEmitter emitter;
        try (var context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            emitter = service.stream(request());
        }
        awaitReleased();
        verify(messages).appendForUser(eq(100L), eq(7L), argThat(command -> "assistant".equals(command.getRole())
                && "真实回复".equals(command.getContent())));
        List<Map<String, Object>> events = events(emitter);
        assertEquals(List.of("MESSAGE_DELTA", "MESSAGE_FINAL"), events.stream().map(item -> item.get("eventType")).toList());
        assertEquals("100", events.getLast().get("conversationId"));
        assertEquals(20L, ((AiChatMessageVO) events.getLast().get("message")).getMessageId());
    }

    @Test
    void failedStreamKeepsOnlyUserMessageAndDoesNotExposeProviderException() throws Exception {
        when(agent.streamMessages(anyList(), any(RunnableConfig.class)))
                .thenReturn(Flux.concat(Flux.just(new AssistantMessage("半截")), Flux.error(new RuntimeException("sk-secret"))));
        SseEmitter emitter;
        try (var context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            emitter = service.stream(request());
        }
        awaitReleased();
        verify(messages, never()).appendForUser(anyLong(), anyLong(), argThat(command -> "assistant".equals(command.getRole())));
        Map<String, Object> error = events(emitter).getLast();
        assertEquals("ERROR", error.get("eventType"));
        assertFalse(error.get("errorMessage").toString().contains("sk-secret"));
    }

    @Test
    void disconnectCancelsModelAndReleasesConversationWithoutPartialAssistant() throws Exception {
        CountDownLatch subscribed = new CountDownLatch(1);
        CountDownLatch cancelled = new CountDownLatch(1);
        when(agent.streamMessages(anyList(), any(RunnableConfig.class))).thenReturn(
                Flux.<Message>never().doOnSubscribe(ignored -> subscribed.countDown()).doOnCancel(cancelled::countDown));
        SseEmitter emitter;
        try (var context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            emitter = service.stream(request());
            assertTrue(subscribed.await(3, TimeUnit.SECONDS));
            assertThrows(BizException.class, () -> service.send(request()));
            ((Runnable) ReflectionTestUtils.getField(emitter, "completionCallback")).run();
            assertTrue(cancelled.await(3, TimeUnit.SECONDS));
            when(agent.streamMessages(anyList(), any(RunnableConfig.class))).thenReturn(Flux.just(new AssistantMessage("重试成功")));
            assertEquals("重试成功", service.send(request()).getMessage().getContent());
        }
        verify(messages, times(1)).appendForUser(anyLong(), anyLong(), argThat(command -> "assistant".equals(command.getRole())));
    }

    @Test
    void timeoutReturnsErrorAndCancelsWithoutSavingAssistant() throws Exception {
        service = new AiAssistantServiceImpl(provider, conversations, messages, 1);
        CountDownLatch cancelled = new CountDownLatch(1);
        when(agent.streamMessages(anyList(), any(RunnableConfig.class)))
                .thenReturn(Flux.<Message>never().doOnCancel(cancelled::countDown));
        SseEmitter emitter;
        try (var context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            emitter = service.stream(request());
        }
        assertTrue(cancelled.await(3, TimeUnit.SECONDS));
        awaitReleased();
        assertEquals("ERROR", events(emitter).getLast().get("eventType"));
        verify(messages, never()).appendForUser(anyLong(), anyLong(), argThat(command -> "assistant".equals(command.getRole())));
    }

    private static AiChatRequest request() {
        AiChatRequest request = new AiChatRequest();
        request.setConversationId(100L); request.setMessage("  新问题  ");
        return request;
    }

    private void awaitReleased() throws InterruptedException {
        Set<?> active = (Set<?>) ReflectionTestUtils.getField(service, "activeConversations");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!active.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(active.isEmpty(), "异步流未正常完成并释放锁");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> events(SseEmitter emitter) {
        Set<ResponseBodyEmitter.DataWithMediaType> data =
                (Set<ResponseBodyEmitter.DataWithMediaType>) ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        return data.stream().map(ResponseBodyEmitter.DataWithMediaType::getData)
                .filter(item -> item instanceof Map).map(item -> (Map<String, Object>) item).toList();
    }
}
