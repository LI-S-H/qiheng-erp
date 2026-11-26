package com.qiheng.erp.ai.conversation;

import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 验证会话列表用最近一轮运行状态回填待处理标记。 */
class AiAssistantServiceImplTest {

    /**
     * 最近一轮停在等待用户处理时，列表摘要必须返回 awaitingUser。
     */
    @Test
    void overviewMarksAwaitingUserFromLatestRunStatus() {
        AiConversationMapper mapper = mock(AiConversationMapper.class);
        AiConversation waiting = new AiConversation()
                .setId(1L)
                .setUserId(9L)
                .setTitle("待确认会话")
                .setDescription("等待确认")
                .setUpdateTime(LocalDateTime.of(2026, 11, 27, 10, 0))
                .setLastRunStatus("WAITING_USER");
        AiConversation completed = new AiConversation()
                .setId(2L)
                .setUserId(9L)
                .setTitle("已完成会话")
                .setDescription("输出完成")
                .setUpdateTime(LocalDateTime.of(2026, 11, 27, 9, 0))
                .setLastRunStatus("COMPLETED");
        when(mapper.selectOverviewByUser(9L)).thenReturn(List.of(waiting, completed));

        var service = new AiAssistantServiceImpl(mapper, null, null, null, null, null, null);
        var overview = service.overview(9L);

        assertThat(overview.conversations()).hasSize(2);
        assertThat(overview.conversations().get(0).awaitingUser()).isTrue();
        assertThat(overview.conversations().get(1).awaitingUser()).isFalse();
    }
}