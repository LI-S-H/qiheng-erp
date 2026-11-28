package com.qiheng.erp.ai.experience;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.AiModelEnabledCondition;
import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 经验提取与合并的模型调用：解析 JSON，失败时把具体原因追加给模型重试。
 *
 * <p>与项目现有的格式校验保持一致：不笼统说“格式错误”，而是指出缺字段、
 * id 非法或输出被截断，让模型能针对性修复。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
@Component
@Conditional(AiModelEnabledCondition.class)
public final class ExperienceModelClient {

    private static final Logger log = LoggerFactory.getLogger(ExperienceModelClient.class);

    /** JSON 不合格时最多重试两次。 */
    private static final int MAX_RETRIES = 2;

    /** 超过十条说明输出质量异常，交给队列标记失败。 */
    public static class ExtractionOutputException extends IllegalArgumentException {
        public ExtractionOutputException(String message) {
            super(message);
        }
    }

    public static final class ExtractionOverflowException extends ExtractionOutputException {
        public ExtractionOverflowException(int count, int maximum) {
            super("经验提取超过 " + maximum + " 条，实际 " + count + " 条");
        }
    }

    /** 合并输出的一条经验。 */
    public record MergeEntry(Long id, String text) {
    }

    /** 合并输出：目录摘要与经验列表。 */
    public record MergeResult(String summary, List<MergeEntry> entries) {
    }

    private final ChatModel model;
    private final ObjectMapper mapper;
    private final OpenAiChatOptions extractionOptions;
    private final OpenAiChatOptions mergeOptions;
    private final int extractionMaxAccepted;

    /**
     * 按配置选择提取模型，默认 flash。
     *
     * @param pro 高质量模型
     * @param flash 快速模型
     * @param mapper JSON 序列化组件
     * @param properties 经验配置
     */
    public ExperienceModelClient(@Qualifier("proChatModel") ChatModel pro,
                                 @Qualifier("flashChatModel") ChatModel flash,
                                 ObjectMapper mapper, AiExperienceProperties properties) {
        this.model = "pro".equalsIgnoreCase(properties.model()) ? pro : flash;
        this.mapper = mapper;
        this.extractionOptions = OpenAiChatOptions.builder()
                .maxTokens(properties.extractionMaxOutputTokens()).build();
        this.mergeOptions = OpenAiChatOptions.builder()
                .maxTokens(properties.mergeMaxOutputTokens()).build();
        this.extractionMaxAccepted = properties.extractionMaxAccepted();
    }

    /**
     * 提取经验文本。
     *
     * @param systemPrompt 提炼提示词
     * @param input 流水转录
     * @return 经验文本列表；失败返回 null
     */
    public List<String> extract(String systemPrompt, String input) {
        return call(systemPrompt, input, this::parseExtraction, extractionOptions, true);
    }

    /**
     * 合并现有经验。
     *
     * @param systemPrompt 合并提示词
     * @param input 现有经验 JSON
     * @return 合并结果；失败返回 null
     */
    public MergeResult merge(String systemPrompt, String input) {
        return call(systemPrompt, input, this::parseMerge, mergeOptions, false);
    }

    /** 调用模型并解析；解析失败时把具体原因追加给模型重试。 */
    private <T> T call(String systemPrompt, String input, Function<String, T> parser,
                       OpenAiChatOptions options, boolean extraction) {
        List<Message> messages = new ArrayList<>(3);
        messages.add(new SystemMessage(systemPrompt));
        messages.add(new UserMessage(input));
        String problem = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            if (problem != null) {
                messages.add(new UserMessage("上次输出有问题：" + problem
                        + "。只返回符合约定的 JSON 对象，不要代码围栏，不要解释。"));
            }
            try {
                ChatResponse response = model.call(new Prompt(messages, options));
                if (response == null || response.getResult() == null) {
                    if (extraction) {
                        return null;
                    }
                    problem = "没有返回内容";
                    continue;
                }
                String finishReason = response.getResult().getMetadata().getFinishReason();
                if ("length".equalsIgnoreCase(finishReason)) {
                    problem = "输出被长度上限截断，请更简洁地输出";
                    if (extraction) {
                        throw new ExtractionOutputException(problem);
                    }
                    continue;
                }
                return parser.apply(response.getResult().getOutput().getText());
            } catch (ExtractionOutputException error) {
                throw error;
            } catch (IllegalArgumentException error) {
                if (extraction) {
                    throw new ExtractionOutputException(error.getMessage());
                }
                problem = error.getMessage();
                log.warn("经验模型输出不合格，准备重试 problem={}", problem);
            } catch (RuntimeException error) {
                // 模型不可用时重试不会改变结果，直接放弃本次任务。
                log.warn("经验模型调用失败 errorType={}", error.getClass().getName());
                return null;
            }
        }
        log.warn("经验模型连续输出不合格，放弃本次调用 problem={}", problem);
        return null;
    }

    /** 解析提取结果。 */
    private List<String> parseExtraction(String text) {
        JsonNode root = readJson(text);
        JsonNode array = root.get("experiences");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("缺少 experiences 数组");
        }
        if (array.size() > extractionMaxAccepted) {
            throw new ExtractionOverflowException(array.size(), extractionMaxAccepted);
        }
        List<String> texts = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonNode item = array.get(index);
            String value = item == null ? null : item.path("text").asText(null);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("第 " + (index + 1) + " 条经验缺少 text");
            }
            texts.add(value);
        }
        return texts;
    }

    /** 解析合并结果。 */
    private MergeResult parseMerge(String text) {
        JsonNode root = readJson(text);
        String summary = root.path("summary").asText(null);
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("缺少 summary");
        }
        JsonNode array = root.get("experiences");
        if (array == null || !array.isArray()) {
            throw new IllegalArgumentException("缺少 experiences 数组");
        }
        List<MergeEntry> entries = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            JsonNode item = array.get(index);
            String value = item == null ? null : item.path("text").asText(null);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("第 " + (index + 1) + " 条经验缺少 text");
            }
            Long id = item.hasNonNull("id") ? item.get("id").asLong() : null;
            entries.add(new MergeEntry(id, value));
        }
        return new MergeResult(summary, entries);
    }

    /** 读取 JSON 对象，失败时给出解析位置。 */
    private JsonNode readJson(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("输出为空");
        }
        try {
            JsonNode root = mapper.readTree(text);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("输出不是 JSON 对象");
            }
            return root;
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("JSON 解析失败：" + summary(error));
        }
    }

    /** 截取解析错误的关键位置，避免提醒过长。 */
    private String summary(JsonProcessingException error) {
        String message = error.getOriginalMessage();
        if (message == null) {
            return "格式不符合约定";
        }
        return message.length() > 160 ? message.substring(0, 160) : message;
    }
}
