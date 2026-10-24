package com.qiheng.erp.ai.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * <p>
 * DeepSeek 模型配置
 * </p>
 *
 * <p>同一供应商的 Pro 与 Flash 模型集中注册，后续子智能体按 Bean 名称选择。
 * 新增供应商（如通义/豆包）另建 Config 类，互不干扰。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AiModelEnabledCondition.class)
public class DeepSeekChatModelConfig {

    @Value("${erp.ai.model.pro:deepseek-v4-pro}")
    private String proModel;

    @Value("${erp.ai.model.flash:deepseek-flash}")
    private String flashModel;

    /** 是否允许模型先思考；空表示不传（非通义系模型不认这个参数）。 */
    @Value("${erp.ai.model.enable-thinking:}")
    private String enableThinking;

    @Value("${erp.ai.model.read-timeout-seconds:300}")
    private long readTimeoutSeconds;

    @Value("${erp.ai.model.flash-read-timeout-seconds:60}")
    private long flashReadTimeoutSeconds;

    /**
     * DeepSeek Pro 模型
     */
    @Bean("deepseekProChatModel")
    public ChatModel deepseekProChatModel(@Value("${erp.ai.api-key}") String apiKey,
                                          @Value("${erp.ai.base-url:https://api.deepseek.com}") String baseUrl,
                                          @Value("${erp.ai.temperature:0.2}") Double temperature) {
        return buildModel(apiKey, baseUrl, temperature, proModel, readTimeoutSeconds);
    }

    /**
     * DeepSeek Flash 模型
     */
    @Bean("deepseekFlashChatModel")
    public ChatModel deepseekFlashChatModel(@Value("${erp.ai.api-key}") String apiKey,
                                            @Value("${erp.ai.base-url:https://api.deepseek.com}") String baseUrl,
                                            @Value("${erp.ai.temperature:0.2}") Double temperature) {
        return buildModel(apiKey, baseUrl, temperature, flashModel, flashReadTimeoutSeconds);
    }

    /** 摘要通常输出较长内容，使用独立读取超时，不依赖旧运行时的首响应保护。 */
    @Bean("deepseekContextSummaryChatModel")
    public ChatModel deepseekContextSummaryChatModel(@Value("${erp.ai.api-key}") String apiKey,
                                                     @Value("${erp.ai.base-url:https://api.deepseek.com}") String baseUrl,
                                                     @Value("${erp.ai.temperature:0.2}") Double temperature,
                                                     @Value("${erp.ai.memory.summary-timeout-seconds:300}") long timeoutSeconds) {
        return buildModel(apiKey, baseUrl, temperature, flashModel, timeoutSeconds);
    }

    /**
     * 构建模型：统一走 OpenAI 兼容装配，网关差异（宽松解析、不自动重试）在那里处理。
     */
    private ChatModel buildModel(String apiKey, String baseUrl, Double temperature, String model, long responseSeconds) {
        Boolean thinking = enableThinking == null || enableThinking.isBlank()
                ? null : Boolean.valueOf(enableThinking.trim());
        return OpenAiCompatibleChatModel.create(apiKey, baseUrl, model, temperature,
                Duration.ofSeconds(Math.max(1, responseSeconds)), thinking);
    }
}
