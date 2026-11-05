package com.qiheng.erp.ai.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.domain.vo.ConversationDetailVO;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.conversation.VisibleMessageCodec;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 旧编排退出后，会话管理与已保存消息仍然独立可用。 */
class AiAssistantHistoryTest {

    private final AiConversationMapper conversations = mock(AiConversationMapper.class);
    private final AiMessageMapper messages = mock(AiMessageMapper.class);
    private final ConversationExecutionLock locks = mock(ConversationExecutionLock.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final AiAssistantServiceImpl service = new AiAssistantServiceImpl(conversations, messages,
            new VisibleMessageCodec(new ObjectMapper()), locks, new TransactionTemplate(transactions));

    @Test
    void invalidConversationCursorIsReportedAsParameterError() {
        assertThatThrownBy(() -> service.pageConversations(1L, "invalid", 2L))
                .isInstanceOf(com.qiheng.erp.common.exception.BizException.class).hasMessageContaining("时间格式错误");
        assertThatThrownBy(() -> service.pageConversations(1L, "2026-01-01 12:00:00", null))
                .isInstanceOf(com.qiheng.erp.common.exception.BizException.class).hasMessageContaining("游标不完整");
    }

    /** 历史会话不再展示旧任务运行中或等待恢复的状态。 */
    @Test
    void existingConversationCanBeReadWithoutLegacyRuntime() {
        when(conversations.selectOwned(10L, 1L)).thenReturn(new AiConversation()
                .setId(10L).setUserId(1L).setTitle("补货分析").setDescription("历史会话"));

        ConversationDetailVO detail = service.detail(10L, 1L);

        assertThat(detail.conversationId()).isEqualTo("10");
        assertThat(detail.title()).isEqualTo("补货分析");
        verifyNoInteractions(messages, locks, transactions);
    }

    /** 新建会话只创建会话记录，不因规划停用而失败。 */
    @Test
    void newConversationCanBeCreatedWithoutLegacyRuntime() {
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        doAnswer(invocation -> {
            AiConversation conversation = invocation.getArgument(0);
            conversation.setId(11L);
            return 1;
        }).when(conversations).insert(any(AiConversation.class));

        ConversationDetailVO detail = service.create(1L);

        assertThat(detail.conversationId()).isEqualTo("11");
        verify(conversations).insert(any(AiConversation.class));
        verifyNoInteractions(messages, locks);
    }

    /** 旧计划、需求和澄清消息仍按保存时的类型从历史分页中读取。 */
    @ParameterizedTest
    @ValueSource(strings = {"PLAN", "NEEDS", "CLARIFICATION"})
    void historicalArtifactsRemainReadable(String type) {
        String content = switch (type) {
            case "PLAN" -> """
                    {"type":"PLAN","data":{"text":"已生成计划","plan":{"title":"补货分析","steps":[]}}}
                    """;
            case "NEEDS" -> """
                    {"type":"NEEDS","data":{"text":"查询库存","needs":[{"id":"N1","need":"查询A仓库库存"}]}}
                    """;
            default -> """
                    {"type":"CLARIFICATION","data":{"text":"请补充时间范围","questions":[{"id":"Q1","question":"查看哪个时间段？","options":[]}],"threadId":"old-thread","pauseId":"old-pause"}}
                    """;
        };
        when(conversations.selectOwned(10L, 1L)).thenReturn(new AiConversation().setId(10L).setUserId(1L));
        when(messages.countVisibleMessages(10L, 1L)).thenReturn(1L);
        when(messages.selectVisibleMessages(10L, 1L, 0, 20)).thenReturn(List.of(new AiMessage()
                .setId(100L).setConversationId(10L).setUserId(1L).setRequestId("old-request")
                .setSeq(2L).setRole("assistant").setKind("ASSISTANT_FINAL")
                .setContent(content).setCreateTime(LocalDateTime.of(2026, 10, 26, 12, 0))));

        var page = service.pageMessages(10L, 1L, 1, 20);

        assertThat(page.total()).isEqualTo(1L);
        assertThat(page.records()).hasSize(1);
        var message = page.records().getFirst();
        assertThat(message.messageId()).isEqualTo("100");
        assertThat(message.requestId()).isEqualTo("old-request");
        assertThat(message.content().type()).isEqualTo("TEXT");
        assertThat(message.content().data()).containsEntry("text", "该历史消息暂时无法展示。");
        verifyNoInteractions(locks, transactions);
    }
}
