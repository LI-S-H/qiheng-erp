package com.qiheng.erp.ai.chatmodel;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/** Pro 是独立模型实例，智能体通过 Bean 名称选择，不受全局模型切换影响。 */
@Configuration(proxyBeanMethods = false)
@Conditional(AiModelEnabledCondition.class)
public class DeepSeekProChatModelConfig {
    @Bean("deepseekProChatModel")
    public ChatModel deepseekProChatModel(@Value("${erp.ai.api-key}") String apiKey,
                                         @Value("${erp.ai.base-url:https://api.deepseek.com}") String baseUrl,
                                         @Value("${erp.ai.temperature:0.2}") Double temperature) {
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build())
                .defaultOptions(OpenAiChatOptions.builder().model("deepseek-v4-pro").temperature(temperature).build())
                .build();
    }
}
