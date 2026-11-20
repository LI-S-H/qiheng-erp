package com.qiheng.erp.ai.experience;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 经验模型客户端的解析与重试契约：失败时把具体原因告诉模型，最多重试两次。
 *
 * @author Li
 * @since 2026-11-20
 */
class ExperienceModelClientTest {

    private static final String EXTRACT_OK = "{\"experiences\":[{\"text\":\"【坑】列名要按实际结果解释。\"}]}";
    private static final String MERGE_OK = "{\"summary\":\"SQL 取数注意列名\","
            + "\"experiences\":[{\"id\":1,\"text\":\"【坑】列名要按实际结果解释。\"},"
            + "{\"id\":null,\"text\":\"【做法】先确认单位。\"}]}";

    @Test
    void extractsValidJson() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(EXTRACT_OK, "stop"));
        var client = client(model);

        List<String> texts = client.extract("system", "input");

        assertThat(texts).containsExactly("【坑】列名要按实际结果解释。");
    }

    @Test
    void invalidJsonIsRetriedWithConcreteReason() throws Exception {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("not json", "stop"), response(EXTRACT_OK, "stop"));
        var client = client(model);

        List<String> texts = client.extract("system", "input");

        assertThat(texts).hasSize(1);
        var captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(captor.capture());
        String reminder = captor.getAllValues().get(1).getInstructions().getLast().getText();
        assertThat(reminder).contains("JSON 解析失败");
    }

    @Test
    void truncatedOutputIsRetried() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(EXTRACT_OK, "length"),
                response(EXTRACT_OK, "stop"));
        var client = client(model);

        assertThat(client.extract("system", "input")).hasSize(1);
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void givesUpAfterTwoRetries() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("", "stop"));
        var client = client(model);

        assertThat(client.extract("system", "input")).isNull();
        verify(model, times(3)).call(any(Prompt.class));
    }

    @Test
    void parsesMergeOutput() {
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response(MERGE_OK, "stop"));
        var client = client(model);

        var result = client.merge("system", "input");

        assertThat(result.summary()).contains("SQL 取数");
        assertThat(result.entries()).hasSize(2);
        assertThat(result.entries().getFirst().id()).isEqualTo(1L);
        assertThat(result.entries().get(1).id()).isNull();
    }

    private static ExperienceModelClient client(ChatModel model) {
        var properties = new AiExperienceProperties(true, "0 10 3 * * ?", 100, 7, 6,
                600_000, 4000, 5, 400, 100, 400, 10, 60, "flash");
        return new ExperienceModelClient(model, model, new ObjectMapper(), properties);
    }

    private static ChatResponse response(String text, String finishReason) {
        var metadata = ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text), metadata)));
    }
}