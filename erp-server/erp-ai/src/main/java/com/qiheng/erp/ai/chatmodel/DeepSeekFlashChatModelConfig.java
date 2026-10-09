package com.qiheng.erp.ai.chatmodel;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Flash 与 Pro 同时存在，后续子智能体可单独注入这个模型。 */
@Configuration(proxyBeanMethods = false)
@Conditional(AiModelEnabledCondition.class)
public class DeepSeekFlashChatModelConfig {
    @Bean("deepseekFlashChatModel")
    public ChatModel deepseekFlashChatModel(@Value("${erp.ai.api-key}") String apiKey,
                                           @Value("${erp.ai.base-url:https://api.deepseek.com}") String baseUrl,
                                           @Value("${erp.ai.temperature:0.2}") Double temperature) {
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build())
                .defaultOptions(OpenAiChatOptions.builder().model("deepseek-flash").temperature(temperature).build())
                .build();
    }
}
