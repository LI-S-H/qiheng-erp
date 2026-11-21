package com.qiheng.erp.ai.agent.context;

import com.qiheng.erp.ai.config.AiModelEnabledCondition;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.config.properties.AiChatProperties;
import com.qiheng.erp.ai.config.properties.AiContextProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 按配置创建上下文压缩 hook。
 *
 * <p>每个智能体需要独立实例，因为 hook 会绑定到具体的智能体图，不能共享。
 * 摘要模型单独装配，读取超时与主对话模型分开：摘要输入可能很长，
 * 沿用对话模型的超时会过早失败。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
@Component
@Conditional(AiModelEnabledCondition.class)
public final class ContextCompressionFactory {

    private final ChatModel summaryModel;
    private final AiContextProperties properties;
    private final String summaryPrompt;
    private final OpenAiChatOptions summaryOptions;

    /**
     * 加载摘要提示词并装配摘要模型。
     *
     * @param chat 模型接入配置
     * @param properties 压缩配置
     * @throws IOException 提示词读取失败
     */
    public ContextCompressionFactory(AiChatProperties chat, AiContextProperties properties) throws IOException {
        this.properties = properties;
        String modelName = "pro".equalsIgnoreCase(properties.summaryModel())
                ? chat.model().pro() : chat.model().flash();
        this.summaryModel = OpenAiCompatibleChatModel.create(chat.apiKey(), chat.baseUrl(), modelName,
                chat.temperature(), Duration.ofSeconds(Math.max(1, properties.summaryTimeoutSeconds())), null);
        this.summaryOptions = OpenAiChatOptions.builder()
                .maxTokens(properties.summaryMaxOutputTokens()).build();
        this.summaryPrompt = new ClassPathResource("prompts/context-summary.md")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 创建一个压缩 hook 实例。
     *
     * @return 压缩 hook
     */
    public ContextCompressionHook create() {
        return new ContextCompressionHook(summaryModel, properties, summaryPrompt, summaryOptions);
    }
}