package com.qiheng.erp.ai.clarification;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.MainAgentService;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 追问答案检查点入口测试。
 *
 * @author Li
 * @since 2026-11-26
 */
class ClarificationConfirmationServiceTest {

    private static final String CALL_ID = "call-1";
    private static final String HITL_NODE = "_AGENT_HOOK_HITL";

    /** 合法答案写回工具结果并恢复运行。 */
    @Test
    void writesAnswerAndResumes() throws Exception {
        Fixture fixture = fixture(HITL_NODE);

        var resumed = fixture.service().resolveForUser(1, 2, CALL_ID,
                List.of(new ClarificationAnswer("warehouse", List.of("一号仓"), "")), "尽快");

        assertThat(resumed.runId()).isEqualTo("run-1");
        invokeApplier(fixture, resumed);
        ArgumentCaptor<Map<String, Object>> state = ArgumentCaptor.forClass(Map.class);
        verify(fixture.graph()).updateState(any(RunnableConfig.class), state.capture());
        var response = (ToolResponseMessage) ((List<?>) state.getValue().get("messages")).getFirst();
        assertThat(response.getResponses().getFirst().responseData())
                .contains("answers")
                .contains("一号仓")
                .contains("尽快");
    }

    /** 答案不合法时抛错，不写检查点。 */
    @Test
    void invalidAnswerIsRejected() throws Exception {
        Fixture fixture = fixture(HITL_NODE);

        assertThatThrownBy(() -> fixture.service().resolveForUser(1, 2, CALL_ID,
                List.of(new ClarificationAnswer("warehouse", List.of("一号仓", "二号仓"), "")), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("只能选择一个");
        verify(fixture.graph(), org.mockito.Mockito.never()).updateState(any(), any());
    }

    /** 检查点已推进时视为确认失效。 */
    @Test
    void expiredCheckpointIsRejected() {
        Fixture fixture = fixture("_AGENT_TOOL_");

        assertThatThrownBy(() -> fixture.service().resolveForUser(1, 2, CALL_ID,
                List.of(new ClarificationAnswer("warehouse", List.of("一号仓"), "")), null))
                .isInstanceOf(MainAgentService.ConfirmationExpiredException.class);
    }

    /** 补充说明过长时拒绝提交。 */
    @Test
    void tooLongNoteIsRejected() {
        Fixture fixture = fixture(HITL_NODE);

        assertThatThrownBy(() -> fixture.service().resolveForUser(1, 2, CALL_ID,
                List.of(new ClarificationAnswer("warehouse", List.of("一号仓"), "")),
                "x".repeat(ClarificationRules.MAX_ANSWER_LENGTH + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("补充说明不能超过");
    }

    /** 编号不匹配时视为确认失效。 */
    @Test
    void wrongCallIdIsRejected() {
        Fixture fixture = fixture(HITL_NODE);

        assertThatThrownBy(() -> fixture.service().resolveForUser(1, 2, "other",
                List.of(new ClarificationAnswer("warehouse", List.of("一号仓"), "")), null))
                .isInstanceOf(MainAgentService.ConfirmationExpiredException.class);
    }

    private Fixture fixture(String nextNode) {
        ReactAgent agent = mock(ReactAgent.class);
        CompiledGraph graph = mock(CompiledGraph.class);
        when(agent.getAndCompileGraph()).thenReturn(graph);

        RedisSaver saver = mock(RedisSaver.class);
        Checkpoint checkpoint = checkpoint(nextNode);
        when(saver.get(any(RunnableConfig.class))).thenReturn(Optional.of(checkpoint));

        MainAgentService runs = mock(MainAgentService.class);
        when(runs.currentRunIdForUser(1, 2)).thenReturn("run-1");
        when(runs.resumeAfterConfirmation(eq(1L), eq(2L), eq("run-1"), any())).thenReturn(Flux.never());

        AiConversationMapper conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        LoginUserService users = mock(LoginUserService.class);
        LoginUser user = new LoginUser();
        user.setPermissionCodes(List.of("ai:assistant:use"));
        when(users.findByUserId(1L)).thenReturn(user);

        ClarificationConfirmationService service = new ClarificationConfirmationService(agent, saver, runs,
                conversations, users, new ObjectMapper());
        return new Fixture(service, runs, graph, checkpoint);
    }

    private Checkpoint checkpoint(String nextNode) {
        var call = new AssistantMessage.ToolCall(CALL_ID, "function", "ask_user", """
                {"questions":[{"id":"warehouse","question":"查哪个仓库？","type":"SINGLE_CHOICE","options":["一号仓","二号仓"]}]}
                """);
        Checkpoint checkpoint = mock(Checkpoint.class);
        when(checkpoint.getNextNodeId()).thenReturn(nextNode);
        when(checkpoint.getId()).thenReturn("checkpoint");
        when(checkpoint.getState()).thenReturn(Map.of("messages", List.of(
                AssistantMessage.builder().toolCalls(List.of(call)).build())));
        return checkpoint;
    }

    private void invokeApplier(Fixture fixture, ClarificationConfirmationService.ResumedRun resumed) throws Exception {
        ArgumentCaptor<MainAgentService.ConfirmationApplier> captor =
                ArgumentCaptor.forClass(MainAgentService.ConfirmationApplier.class);
        verify(fixture.runs()).resumeAfterConfirmation(eq(1L), eq(2L), eq(resumed.runId()), captor.capture());
        captor.getValue().apply(RunnableConfig.builder().threadId("main:1:2").build(), fixture.checkpoint());
    }

    private record Fixture(ClarificationConfirmationService service, MainAgentService runs,
                           CompiledGraph graph, Checkpoint checkpoint) {
    }
}