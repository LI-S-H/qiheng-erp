package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 追问校验的可见消息与流水边界测试。
 *
 * @author Li
 * @since 2026-11-26
 */
class AgentRunRecorderAskUserTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ConversationMessageStore store = mock(ConversationMessageStore.class);
    private final AiAgentTraceMapper traceMapper = mock(AiAgentTraceMapper.class);
    private final AgentRunRecorder recorder = new AgentRunRecorder(store, mapper,
            new AiAgentTraceRecorder(traceMapper, mapper, new AiAgentTraceRecorder.Scope(1, 2, "run-1", "MAIN")),
            1, 2, "run-1");

    /** 非法追问结果只写流水，不写可见消息。 */
    @Test
    void invalidAskUserResultOnlyWritesTrace() {
        recorder.beforeModel(List.of(toolResponse("call-1", "ask_user",
                "{\"status\":\"invalid_arguments\",\"errors\":[{\"path\":\"questions[0].id\"}]}")));

        verify(store, never()).append(any(AiMessage.class));
        verify(traceMapper).insert(eq(1L), eq(2L), eq("run-1"), eq("MAIN"), eq("TOOL_RESULT"), any());
    }

    /** 合法追问结果正常写可见消息。 */
    @Test
    void validAskUserResultIsVisible() {
        recorder.beforeModel(List.of(toolResponse("call-2", "ask_user",
                "{\"answers\":[{\"questionId\":\"q\",\"selectedOptions\":[\"A\"],\"customText\":\"\"}]}")));

        ArgumentCaptor<AiMessage> captor = ArgumentCaptor.forClass(AiMessage.class);
        verify(store).append(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo("TOOL_RESULT");
        assertThat(captor.getValue().getContent()).contains("answers");
    }

    /** 终止失败仍保存最终说明，并记录失败标记。 */
    @Test
    void terminalValidationFailureStillSavesFinalMessage() {
        recorder.markClarificationValidationFailed();
        NodeOutput output = mock(NodeOutput.class);
        when(output.isEND()).thenReturn(true);
        when(output.state()).thenReturn(new OverAllState(Map.of("messages", List.of(
                new AssistantMessage("抱歉，这次没能把需要补充的条件问清楚。")))));

        recorder.capture(output);

        assertThat(recorder.clarificationValidationFailed()).isTrue();
        ArgumentCaptor<AiMessage> captor = ArgumentCaptor.forClass(AiMessage.class);
        verify(store).append(captor.capture());
        assertThat(captor.getValue().getKind()).isEqualTo("ASSISTANT_FINAL");
        assertThat(captor.getValue().getContent()).contains("没能把需要补充的条件问清楚");
    }

    private ToolResponseMessage toolResponse(String callId, String name, String data) {
        return ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(callId, name, data)))
                .build();
    }
}