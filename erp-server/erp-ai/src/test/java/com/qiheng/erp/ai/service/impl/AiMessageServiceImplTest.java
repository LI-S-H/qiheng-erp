package com.qiheng.erp.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.dto.AiMessageCreateCommand;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.service.IAiConversationService;
import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.security.context.UserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiMessageServiceImplTest {

    @Mock
    private AiMessageMapper messageMapper;

    @Mock
    private AiConversationMapper conversationMapper;

    @Mock
    private IAiConversationService conversationService;

    @Test
    void historyShouldUseOwnedConversationAndReturnEmptyStructuredArrays() {
        AiConversation conversation = new AiConversation().setId(1001L).setUserId(7L);
        when(conversationService.getOwnedConversation(1001L)).thenReturn(conversation);
        when(messageMapper.selectPage(any(Page.class), any(Wrapper.class))).thenAnswer(invocation -> {
            Page<AiMessage> page = invocation.getArgument(0);
            page.setRecords(List.of(new AiMessage()
                    .setId(2001L)
                    .setConversationId(1001L)
                    .setUserId(7L)
                    .setRole("assistant")
                    .setContent("真实历史消息")
                    .setCreateTime(LocalDateTime.now())));
            page.setTotal(1);
            return page;
        });
        AiMessageServiceImpl service = new AiMessageServiceImpl(
                messageMapper, conversationMapper, conversationService, new ObjectMapper());

        try (MockedStatic<UserContext> context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            var result = service.pageHistory(1001L, new PageQuery());

            assertEquals(1, result.getTotal());
            assertEquals(2001L, result.getRecords().getFirst().getMessageId());
            assertTrue(result.getRecords().getFirst().getCharts().isArray());
            assertTrue(result.getRecords().getFirst().getCharts().isEmpty());
            verify(conversationService).getOwnedConversation(1001L);
        }
    }

    @Test
    void appendShouldTakeUserIdFromContextAndAdvanceConversationTime() {
        AiConversation conversation = new AiConversation()
                .setId(1001L)
                .setUserId(7L)
                .setVersion(0);
        when(conversationService.getOwnedConversation(1001L)).thenReturn(conversation);
        when(messageMapper.insert(any(AiMessage.class))).thenAnswer(invocation -> {
            AiMessage message = invocation.getArgument(0);
            message.setId(2001L);
            return 1;
        });
        when(conversationMapper.updateById(any(AiConversation.class))).thenReturn(1);
        AiMessageServiceImpl service = new AiMessageServiceImpl(
                messageMapper, conversationMapper, conversationService, new ObjectMapper());
        AiMessageCreateCommand command = new AiMessageCreateCommand();
        command.setRole("USER");
        command.setContent("  查询库存  ");

        try (MockedStatic<UserContext> context = mockStatic(UserContext.class)) {
            context.when(UserContext::getUserId).thenReturn(7L);
            var result = service.append(1001L, command);

            ArgumentCaptor<AiMessage> captor = ArgumentCaptor.forClass(AiMessage.class);
            verify(messageMapper).insert(captor.capture());
            assertEquals(7L, captor.getValue().getUserId());
            assertEquals("user", captor.getValue().getRole());
            assertEquals("查询库存", captor.getValue().getContent());
            assertEquals(2001L, result.getMessageId());
            assertTrue(conversation.getLastMessageAt() != null);
        }
    }
}
