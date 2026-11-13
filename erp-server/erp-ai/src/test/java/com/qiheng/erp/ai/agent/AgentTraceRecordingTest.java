package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 核实主智能体一次运行写入的智能体流水。
 *
 * <p>流水是排障与经验提取的事实来源：每个事件都要带上智能体类型、任务号和原文，
 * 且同一条助手消息的模型原话只记一次。</p>
 */
class AgentTraceRecordingTest {

    /** 一次运行写全输入、模型原话、工具调用、工具结果和最终答复。 */
    @Test
    void recordsRunInputModelOutputCallResultAndFinal() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var mapper = new ObjectMapper();
        var recorder = new AgentRunRecorder(mock(ConversationMessageStore.class), mapper,
                new AiAgentTraceRecorder(traces, mapper, new AiAgentTraceRecorder.Scope(1, 2, "run-1", "MAIN")),
                1, 2, "run-1");

        recorder.start("查询库存");
        var call = new AssistantMessage.ToolCall("c1", "function", "readDataset", "{\"datasetId\":\"ds_1\"}");
        var toolCall = AssistantMessage.builder().content("先读数据集").toolCalls(List.of(call)).build();
        var result = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("c1", "readDataset", "{\"rows\":[]}"))).build();
        recorder.toolCall("c1", "readDataset", "{\"datasetId\":\"ds_1\"}", toolCall);
        recorder.beforeModel(List.of(toolCall, result));
        recorder.capture(endOutput(List.of(new UserMessage("查询库存"), toolCall, result,
                new AssistantMessage("库存充足"))));

        var events = capturedEvents(traces, mapper);
        assertThat(events).extracting(TraceEvent::eventType)
                .containsExactly("INPUT", "MODEL_OUTPUT", "TOOL_CALL", "TOOL_RESULT", "FINAL");
        assertThat(events).extracting(TraceEvent::agentType).containsOnly("MAIN");
        assertThat(events).extracting(TraceEvent::taskId).containsOnly("run-1");
        assertThat(events.get(0).content().path("text").asText()).isEqualTo("查询库存");
        assertThat(events.get(1).content().path("text").asText()).isEqualTo("先读数据集");
        assertThat(events.get(2).content().path("name").asText()).isEqualTo("readDataset");
        assertThat(events.get(2).content().path("text").asText()).contains("ds_1");
        assertThat(events.get(3).content().path("text").asText()).contains("rows");
        assertThat(events.get(4).content().path("text").asText()).isEqualTo("库存充足");
    }

    /** 同一条助手消息带多个工具调用时，模型原话只记一次。 */
    @Test
    void recordsModelOutputOncePerAssistantMessage() throws Exception {
        var traces = mock(AiAgentTraceMapper.class);
        var mapper = new ObjectMapper();
        var recorder = new AgentRunRecorder(mock(ConversationMessageStore.class), mapper,
                new AiAgentTraceRecorder(traces, mapper, new AiAgentTraceRecorder.Scope(1, 2, "run-2", "MAIN")),
                1, 2, "run-2");

        var calls = List.of(new AssistantMessage.ToolCall("c1", "function", "readDataset", "{}"),
                new AssistantMessage.ToolCall("c2", "function", "listDatasets", "{}"));
        var toolCall = AssistantMessage.builder().content("同时读两个来源").toolCalls(calls).build();
        recorder.toolCall("c1", "readDataset", "{}", toolCall);
        recorder.toolCall("c2", "listDatasets", "{}", toolCall);

        var events = capturedEvents(traces, mapper);
        assertThat(events).extracting(TraceEvent::eventType)
                .containsExactly("MODEL_OUTPUT", "TOOL_CALL", "TOOL_CALL");
    }

    /** 模拟框架正常执行到结束节点。 */
    private static NodeOutput endOutput(List<?> messages) {
        var output = mock(NodeOutput.class);
        when(output.isEND()).thenReturn(true);
        when(output.state()).thenReturn(new OverAllState(Map.of("messages", messages)));
        return output;
    }

    /** 读取写入的流水事件；参数顺序与 Mapper 一致。 */
    private static List<TraceEvent> capturedEvents(AiAgentTraceMapper traces, ObjectMapper mapper) throws Exception {
        var taskId = ArgumentCaptor.forClass(String.class);
        var agentType = ArgumentCaptor.forClass(String.class);
        var eventType = ArgumentCaptor.forClass(String.class);
        var content = ArgumentCaptor.forClass(String.class);
        verify(traces, atLeastOnce()).insert(anyLong(), anyLong(), taskId.capture(), agentType.capture(),
                eventType.capture(), content.capture());
        var events = new ArrayList<TraceEvent>();
        for (int index = 0; index < eventType.getAllValues().size(); index++) {
            events.add(new TraceEvent(taskId.getAllValues().get(index), agentType.getAllValues().get(index),
                    eventType.getAllValues().get(index), mapper.readTree(content.getAllValues().get(index))));
        }
        return events;
    }

    /** 一条流水事件。 */
    private record TraceEvent(String taskId, String agentType, String eventType, JsonNode content) {
    }
}
