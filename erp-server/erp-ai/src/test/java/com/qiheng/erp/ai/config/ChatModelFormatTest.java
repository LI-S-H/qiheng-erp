package com.qiheng.erp.ai.config;

import com.qiheng.erp.ai.config.properties.AiChatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.ResponseFormat;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证不同用途模型的输出选项，避免 JSON 模式影响智能体。 */
class ChatModelFormatTest {
    @Test
    void onlySqlModelRequiresJsonObject() {
        var chat = new AiChatProperties("test-key", "http://localhost:9999", 0.2, null);
        var config = new ChatModelConfiguration();

        assertThat(options(config.proChatModel(chat)).getResponseFormat()).isNull();
        ChatModel flash = config.flashChatModel(chat);
        assertThat(options(flash).getResponseFormat()).isNull();
        assertThat(options(SqlGraphConfiguration.sqlModel((OpenAiChatModel) flash)).getResponseFormat().getType())
                .isEqualTo(ResponseFormat.Type.JSON_OBJECT);
        assertThat(options(flash).getResponseFormat()).isNull();
    }

    /** 获取网关模型实际使用的默认选项。 */
    private OpenAiChatOptions options(ChatModel model) {
        return (OpenAiChatOptions) model.getDefaultOptions();
    }
}
