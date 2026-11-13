package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证框架 outputType 遇到错误文本时的实际行为。 */
class AnalysisHandoffFormatProbeTest {
    record Handoff(String completion, String summary) {
    }

    @Test
    void observesMalformedFinalResponse() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage("不是 JSON")))));
        var agent = ReactAgent.builder().name("bad_handoff_probe").model(model)
                .systemPrompt("完成分析并返回交接结果。")
                .outputType(Handoff.class).build();

        String text = agent.call("分析库存").getText();
        System.out.println("MALFORMED_FRAMEWORK_RESULT=" + text);
        assertThat(text).isEqualTo("不是 JSON");
        verify(model, times(1)).call(any(Prompt.class));
        assertThatThrownBy(() -> new ObjectMapper().readValue(text, Handoff.class)).isNotNull();
    }

    /** 合法 JSON 仍可能缺字段或包含不受支持的状态。 */
    @Test
    void observesInvalidFieldsInJson() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"completion\":\"UNKNOWN\"}")))));
        var agent = ReactAgent.builder().name("invalid_handoff_probe").model(model)
                .systemPrompt("完成分析并返回交接结果。")
                .outputType(Handoff.class).build();

        String text = agent.call("分析库存").getText();
        var handoff = new ObjectMapper().readValue(text, Handoff.class);
        System.out.println("INVALID_FIELDS_FRAMEWORK_RESULT=" + text);
        assertThat(handoff.completion()).isEqualTo("UNKNOWN");
        assertThat(handoff.summary()).isNull();
        verify(model, times(1)).call(any(Prompt.class));
    }
}
