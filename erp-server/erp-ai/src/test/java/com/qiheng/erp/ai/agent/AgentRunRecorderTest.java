package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import static org.mockito.Mockito.mock;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;

class AgentRunRecorderTest {
    @Test
    void recordsCompleteMessagesOnceAndExposesOnlyQuestionTool() {
        var store = mock(ConversationMessageStore.class);
        var recorder = new AgentRunRecorder(store, new ObjectMapper(), traceRecorder(), 1, 2, "run");
        recorder.start("查询库存");
        var call = new AssistantMessage.ToolCall("c1", "function", "ask_user", "{\"questions\":[]}");
        var toolCall = AssistantMessage.builder().content("请先确认仓库").toolCalls(List.of(call)).build();
        var result = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("c1", "ask_user", "{\"answer\":\"一号仓\"}"))).build();
        var messages = List.of(new UserMessage("历史"), new UserMessage("查询库存"), toolCall, result);
        var output = NodeOutput.of("tool", "main_agent", new OverAllState(Map.of("messages", messages)), null);
        recorder.toolCall("c1", "ask_user", "{\"questions\":[]}", toolCall);
        recorder.beforeModel(messages);

        var saved = ArgumentCaptor.forClass(AiMessage.class);
        verify(store, times(4)).append(saved.capture());
        assertThat(saved.getAllValues()).extracting(AiMessage::getKind)
                .containsExactly("USER_INPUT", "ASSISTANT_STEP", "TOOL_CALL", "TOOL_RESULT");
        assertThat(saved.getAllValues().get(2).getContent()).contains("c1", "ask_user");
        verify(store).updateRunStatus(1, 2, "run", "RUNNING", null);
    }
    /** 工具返回只在流水里，消息表只写追问的回答。 */
    @Test
    void writesOnlyAskUserResultsToMessages() {
        var store = mock(ConversationMessageStore.class);
        var recorder = new AgentRunRecorder(store, new ObjectMapper(), traceRecorder(), 1, 2, "run");
        var result = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("a", "query_sql_data", "{\"rows\":[]}"),
                new ToolResponseMessage.ToolResponse("b", "ask_user", "{\"answer\":\"一号仓\"}"))).build();
        recorder.beforeModel(List.of(result));
        var saved = ArgumentCaptor.forClass(AiMessage.class);
        verify(store).append(saved.capture());
        assertThat(saved.getValue().getMessageKey()).isEqualTo("run:result:b");
        assertThat(saved.getValue().getKind()).isEqualTo("TOOL_RESULT");
    }

    /** 普通工具结束时写一行结构化展示：工具名、文案、状态与耗时。 */
    @Test
    void storesFinishedToolAsStructuredRow() {
        var store = mock(ConversationMessageStore.class);
        var recorder = new AgentRunRecorder(store, new ObjectMapper(), traceRecorder(), 1, 2, "run");
        var call = new AssistantMessage.ToolCall("call-1", "function", "query_sql_data", "{}");
        recorder.toolCall("call-1", "query_sql_data", "{}",
                AssistantMessage.builder().toolCalls(List.of(call)).build());
        recorder.toolFinished("call-1", "查询业务数据", "returned", 1250);
        var saved = ArgumentCaptor.forClass(AiMessage.class);
        verify(store).append(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo("TOOL_CALL");
        assertThat(saved.getValue().getMessageKey()).isEqualTo("run:tool:call-1");
        assertThat(saved.getValue().getContent())
                .contains("query_sql_data", "查询业务数据", "1250", "done");
    }

    /** 追问在调用开始时写一行，结束时不再重复。 */
    @Test
    void writesAskUserOnceAtCallTime() {
        var store = mock(ConversationMessageStore.class);
        var recorder = new AgentRunRecorder(store, new ObjectMapper(), traceRecorder(), 1, 2, "run");
        var call = new AssistantMessage.ToolCall("ask-1", "function", "ask_user", "{\"questions\":[]}");
        recorder.toolCall("ask-1", "ask_user", "{\"questions\":[]}",
                AssistantMessage.builder().toolCalls(List.of(call)).build());
        recorder.toolFinished("ask-1", "补充信息", "returned", 900);
        var saved = ArgumentCaptor.forClass(AiMessage.class);
        verify(store).append(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo("TOOL_CALL");
        assertThat(saved.getValue().getContent()).contains("ask_user", "questions");
    }

    @Test
    void storesAnalysisProcessAsVisibleBlock() {
        var store = mock(ConversationMessageStore.class);
        var recorder = new AgentRunRecorder(store, new ObjectMapper(), traceRecorder(), 1, 2, "run");
        recorder.analysis(Map.of("toolCallId", "call-1", "goal", "分析库存",
                "steps", List.of(Map.of("text", "先看数据", "tools", List.of())),
                "completion", "COMPLETED", "summary", "库存充足"));
        var saved = ArgumentCaptor.forClass(AiMessage.class);
        verify(store).append(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo("ANALYSIS_STEP");
        assertThat(saved.getValue().getMessageKey()).isEqualTo("run:analysis:call-1");
        assertThat(saved.getValue().getContent()).contains("分析库存", "库存充足");
    }

    /** 模型失败时框架的错误文本不能作为最终答复落库，只留在流水里。 */
    @Test
    void modelFailureIsRecordedAsFailureWithoutVisibleAnswer() throws Exception {
        var store = mock(ConversationMessageStore.class);
        var traces = mock(AiAgentTraceMapper.class);
        var mapper = new ObjectMapper();
        var recorder = new AgentRunRecorder(store, mapper,
                traceRecorder(traces), 1, 2, "run");
        var output = NodeOutput.of("__END__", "main_agent", new OverAllState(Map.of("messages",
                List.of(new AssistantMessage("Exception: 上游超时")))), null);

        recorder.capture(output);

        org.mockito.Mockito.verifyNoInteractions(store);
        var content = ArgumentCaptor.forClass(String.class);
        var eventType = ArgumentCaptor.forClass(String.class);
        verify(traces).insert(eq(1L), eq(2L), eq("run"), eq("MAIN"), eventType.capture(), content.capture());
        assertThat(eventType.getValue()).isEqualTo("MODEL_FAILURE");
        assertThat(content.getValue()).contains("Exception: 上游超时");
        assertThat(recorder.modelFailed()).isTrue();
    }

    /** 分析过程行写入失败只记日志，不能把已经算好的结论一起丢掉。 */
    @Test
    void analysisWriteFailureDoesNotPropagate() {
        var store = mock(ConversationMessageStore.class);
        when(store.append(any())).thenThrow(new IllegalStateException("写入失败"));
        var recorder = new AgentRunRecorder(store, new ObjectMapper(), traceRecorder(), 1, 2, "run");

        assertDoesNotThrow(() -> recorder.analysis(Map.of("toolCallId", "call-1", "goal", "分析库存")));
    }

    /** 建立只写不校验的流水记录器；单元测试不连库。 */
    private static AiAgentTraceRecorder traceRecorder() {
        return new AiAgentTraceRecorder(mock(AiAgentTraceMapper.class), new ObjectMapper(),
                new AiAgentTraceRecorder.Scope(1, 2, "run", "MAIN"));
    }

    /** 使用指定流水通道建立记录器，便于校验写入内容。 */
    private static AiAgentTraceRecorder traceRecorder(AiAgentTraceMapper traces) {
        return new AiAgentTraceRecorder(traces, new ObjectMapper(),
                new AiAgentTraceRecorder.Scope(1, 2, "run", "MAIN"));
    }
}
