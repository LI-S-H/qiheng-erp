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
import static org.mockito.Mockito.*;

class AgentRunRecorderTest {
    @Test
    void recordsCompleteMessagesOnceAndExposesOnlyQuestionTool() {
        var store = mock(ConversationMessageStore.class);
        var recorder = new AgentRunRecorder(store, new ObjectMapper(), 1, 2, "run");
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
        assertThat(saved.getAllValues()).extracting(AiMessage::getVisible).containsOnly(true);
        assertThat(saved.getAllValues().get(2).getContent()).contains("c1", "ask_user");
        verify(store).updateRunStatus(1, 2, "run", "RUNNING", null);
    }
    @Test
    void recordsEveryResponseFromOneToolMessage() {
        var store = mock(ConversationMessageStore.class);
        var recorder = new AgentRunRecorder(store, new ObjectMapper(), 1, 2, "run");
        var result = ToolResponseMessage.builder().responses(List.of(
                new ToolResponseMessage.ToolResponse("a", "first", "A"),
                new ToolResponseMessage.ToolResponse("b", "second", "B"))).build();
        recorder.beforeModel(List.of(result));
        var saved = ArgumentCaptor.forClass(AiMessage.class);
        verify(store, times(2)).append(saved.capture());
        assertThat(saved.getAllValues()).extracting(AiMessage::getMessageKey)
                .containsExactly("run:result:a", "run:result:b");
    }

    @Test
    void storesOnlyTheReadableToolDuration() {
        var store = mock(ConversationMessageStore.class);
        var recorder = new AgentRunRecorder(store, new ObjectMapper(), 1, 2, "run");
        recorder.toolFinished("call-1", "正在查询业务数据", 1250);
        var saved = ArgumentCaptor.forClass(AiMessage.class);
        verify(store).append(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo("ASSISTANT_STEP");
        assertThat(saved.getValue().getContent()).contains("查询业务数据", "1.3 秒");
        assertThat(saved.getValue().getVisible()).isTrue();
    }
}
