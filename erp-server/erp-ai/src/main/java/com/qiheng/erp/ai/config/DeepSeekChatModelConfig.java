package com.qiheng.erp.ai.config;

import com.qiheng.erp.ai.config.properties.AiChatProperties;
import com.qiheng.erp.ai.config.properties.AiMemoryProperties;
import org.springframework.ai.chat.model.ChatModel;
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
 * <p>配置从 {@link AiChatProperties} 取，三个 Bean 共用同一份网关参数，
 * 改地址或密钥只改一处。摘要模型额外复用 {@link AiMemoryProperties} 的超时，
 * 与上下文压缩器保持同一个值。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AiModelEnabledCondition.class)
public class DeepSeekChatModelConfig {

    /**
     * DeepSeek Pro 模型
     */
    @Bean("deepseekProChatModel")
    public ChatModel deepseekProChatModel(AiChatProperties chat) {
        return buildModel(chat, chat.model().pro(), chat.model().readTimeoutSeconds());
    }

    /**
     * DeepSeek Flash 模型
     */
    @Bean("deepseekFlashChatModel")
    public ChatModel deepseekFlashChatModel(AiChatProperties chat) {
        return buildModel(chat, chat.model().flash(), chat.model().flashReadTimeoutSeconds());
    }

    /** 摘要通常输出较长内容，使用独立读取超时，不依赖旧运行时的首响应保护。 */
    @Bean("deepseekContextSummaryChatModel")
    public ChatModel deepseekContextSummaryChatModel(AiChatProperties chat, AiMemoryProperties memory) {
        return buildModel(chat, chat.model().flash(), memory.summaryTimeoutSeconds());
    }

    /**
     * 构建模型：统一走 OpenAI 兼容装配，网关差异（宽松解析、不自动重试）在那里处理。
     */
    private ChatModel buildModel(AiChatProperties chat, String model, long responseSeconds) {
        String thinking = chat.model().enableThinking();
        Boolean flag = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        return OpenAiCompatibleChatModel.create(chat.apiKey(), chat.baseUrl(), model,
                chat.temperature(), Duration.ofSeconds(Math.max(1, responseSeconds)), flag);
    }
}