package com.qiheng.erp.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qiheng.erp.ai.domain.dto.AiConversationUpdateRequest;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.security.context.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiConversationServiceImplTest {

    @Mock
    private AiConversationMapper conversationMapper;

    @Mock
    private AiMessageMapper messageMapper;

    @Test
    void createShouldAlwaysUseCurrentUserId() {
        AiConversationServiceImpl service = new AiConversationServiceImpl(conversationMapper, messageMapper);
        when(conversationMapper.insert(any(AiConversation.class))).thenAnswer(invocation -> {
            AiConversation conversation = invocation.getArgument(0);
            conversation.setId(1001L);
            return 1;
        });

        try (MockedStatic<UserContext> context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            var result = service.create();

            assertEquals(1001L, result.getConversationId());
            verify(conversationMapper).insert(any(AiConversation.class));
        }
    }

    @Test
    void overviewShouldReturnOnlyPersistedConversationsAndEmptyQuickPrompts() {
        LocalDateTime now = LocalDateTime.now();
        AiConversation conversation = new AiConversation()
                .setId(1001L)
                .setUserId(7L)
                .setTitle("库存分析")
                .setDescription("真实会话")
                .setLastMessageAt(now);
        when(conversationMapper.selectList(any(Wrapper.class))).thenReturn(List.of(conversation));
        AiConversationServiceImpl service = new AiConversationServiceImpl(conversationMapper, messageMapper);

        try (MockedStatic<UserContext> context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            var result = service.overview();

            assertEquals(1, result.getConversations().size());
            assertEquals(1001L, result.getConversations().getFirst().getConversationId());
            assertTrue(result.getQuickPrompts().isEmpty());
        }
    }

    @Test
    void crossUserConversationShouldBeReportedAsNotFound() {
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(null);
        AiConversationServiceImpl service = new AiConversationServiceImpl(conversationMapper, messageMapper);

        try (MockedStatic<UserContext> context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            BizException exception = assertThrows(BizException.class,
                    () -> service.getOwnedConversation(999L));
            assertEquals(ErrorCode.AI_CONVERSATION_NOT_FOUND.getCode(), exception.getCode());
        }
    }

    @Test
    void deleteShouldLogicallyDeleteMessagesBeforeConversationInOneServiceCall() {
        AiConversation conversation = new AiConversation().setId(1001L).setUserId(7L);
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conversation);
        when(messageMapper.delete(any(Wrapper.class))).thenReturn(2);
        when(conversationMapper.delete(any(Wrapper.class))).thenReturn(1);
        AiConversationServiceImpl service = new AiConversationServiceImpl(conversationMapper, messageMapper);

        try (MockedStatic<UserContext> context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            service.delete(1001L);

            InOrder order = inOrder(messageMapper, conversationMapper);
            order.verify(messageMapper).delete(any(Wrapper.class));
            order.verify(conversationMapper).delete(any(Wrapper.class));
        }
    }

    @Test
    void updateShouldTrimTitle() {
        AiConversation conversation = new AiConversation()
                .setId(1001L)
                .setUserId(7L)
                .setTitle("旧标题")
                .setVersion(0);
        when(conversationMapper.selectOne(any(Wrapper.class))).thenReturn(conversation);
        when(conversationMapper.updateById(any(AiConversation.class))).thenReturn(1);
        AiConversationServiceImpl service = new AiConversationServiceImpl(conversationMapper, messageMapper);
        AiConversationUpdateRequest request = new AiConversationUpdateRequest();
        request.setTitle("  新标题  ");

        try (MockedStatic<UserContext> context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            var result = service.update(1001L, request);
            assertEquals("新标题", result.getTitle());
        }
    }
}
