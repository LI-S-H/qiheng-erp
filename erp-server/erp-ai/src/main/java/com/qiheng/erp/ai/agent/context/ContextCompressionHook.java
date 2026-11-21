package com.qiheng.erp.ai.agent.context;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.qiheng.erp.ai.config.properties.AiContextProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 在模型调用前压缩过长的消息历史。
 *
 * <p>超过阈值时保留最近一段原文，把更早的消息交给摘要模型生成结构化交接摘要，
 * 用一条用户消息替换掉被压缩的历史。摘要失败、输出被截断或未按模板填写时，
 * 一律放弃本次压缩并返回原列表，绝不因为摘要出错丢掉历史。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
@HookPositions({HookPosition.BEFORE_MODEL})
public final class ContextCompressionHook extends MessagesModelHook {

    private static final Logger log = LoggerFactory.getLogger(ContextCompressionHook.class);

    /** 摘要消息前缀，后续压缩时据此识别已有摘要。 */
    public static final String SUMMARY_PREFIX = CompressionTranscript.SUMMARY_PREFIX;

    /** 模板校验要求的标题。 */
    private static final List<String> REQUIRED_HEADINGS = List.of("## 目标", "## 工作状态", "## 下一步");

    /** 切割点最多前移的消息数，避免为找安全点跳过太多内容。 */
    private static final int MAX_CUTOFF_SHIFT = 5;

    private final ChatModel summaryModel;
    private final AiContextProperties properties;
    private final String summaryPrompt;
    private final ChatOptions summaryOptions;

    /**
     * 建立压缩 hook。
     *
     * @param summaryModel 摘要模型
     * @param properties 压缩配置
     * @param summaryPrompt 摘要系统提示词
     * @param summaryOptions 摘要调用的选项，可为空
     */
    public ContextCompressionHook(ChatModel summaryModel, AiContextProperties properties,
                                  String summaryPrompt, ChatOptions summaryOptions) {
        this.summaryModel = summaryModel;
        this.properties = properties;
        this.summaryPrompt = summaryPrompt;
        this.summaryOptions = summaryOptions;
    }

    /**
     * 在模型调用前检查并压缩历史。
     *
     * @param messages 当前消息列表
     * @param config 运行配置
     * @return 压缩后的命令；不压缩时返回不改动状态的命令
     */
    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        if (!properties.enabled() || messages == null || messages.size() <= properties.minKeepMessages()) {
            return unchanged();
        }
        int before = TokenEstimator.estimate(messages);
        if (before < properties.maxTokensBeforeSummary()) {
            return unchanged();
        }
        int cutoff = safeCutoff(messages);
        if (cutoff <= 0) {
            log.warn("压缩未找到安全切割点 messages={} tokens={}", messages.size(), before);
            return unchanged();
        }
        CompressionTranscript.Result transcript = CompressionTranscript.render(
                messages.subList(0, cutoff), properties.summaryInputMaxTokens(),
                properties.sourceToolClipChars(), properties.sourceMessageClipChars());
        if (transcript == null) {
            log.warn("压缩输入无法放入预算 beforeTokens={}", before);
            return unchanged();
        }
        long startedAt = System.currentTimeMillis();
        String summary = summarize(transcript.text());
        if (summary == null) {
            log.warn("摘要生成失败，保留原始历史 beforeTokens={} summarized={}", before, transcript.summarizedMessages());
            return unchanged();
        }
        List<Message> compressed = buildCompressed(messages, cutoff, summary);
        int after = TokenEstimator.estimate(compressed);
        log.info("上下文压缩完成 beforeTokens={} afterTokens={} summarized={} kept={} omittedExchanges={} "
                        + "summaryChars={} elapsedMs={}", before, after, transcript.summarizedMessages(),
                messages.size() - cutoff, transcript.omittedExchanges(), summary.length(),
                System.currentTimeMillis() - startedAt);
        return new AgentCommand(compressed);
    }

    /** 提供本 hook 的名称。 */
    @Override
    public String getName() {
        return "context_compression";
    }

    /** 排在消息记录与追踪之后、经验注入之前，保证流水先于压缩写入。 */
    @Override
    public int getOrder() {
        return 10;
    }

    /** 返回不改动状态、也不写入原列表的命令，避免状态被重复追加。 */
    private AgentCommand unchanged() {
        return new AgentCommand((List<Message>) null);
    }

    /** 从尾部按 token 预算找保留起点，并调整到不切断工具配对的安全位置。 */
    private int safeCutoff(List<Message> messages) {
        int size = messages.size();
        int minKeep = Math.min(properties.minKeepMessages(), size);
        int index = size;
        int tokens = 0;
        while (index > 0) {
            boolean enoughMessages = size - index >= minKeep;
            if (enoughMessages && tokens >= properties.keepTokens()) {
                break;
            }
            index--;
            tokens += TokenEstimator.estimate(messages.get(index));
        }
        int cutoff = index;
        int shifted = 0;
        while (cutoff < size && !isSafeCutoff(messages, cutoff) && shifted < MAX_CUTOFF_SHIFT) {
            cutoff++;
            shifted++;
        }
        return isSafeCutoff(messages, cutoff) ? cutoff : -1;
    }

    /** 切割点前不能留下没有对应调用的工具响应，也不能把调用与响应分开。 */
    private boolean isSafeCutoff(List<Message> messages, int cutoff) {
        if (cutoff <= 0 || cutoff >= messages.size()) {
            return false;
        }
        if (messages.get(cutoff) instanceof ToolResponseMessage) {
            return false;
        }
        return !(messages.get(cutoff - 1) instanceof AssistantMessage previous && !previous.getToolCalls().isEmpty());
    }

    /** 组装摘要消息与保留的最近原文。 */
    private List<Message> buildCompressed(List<Message> messages, int cutoff, String summary) {
        List<Message> compressed = new ArrayList<>(messages.size() - cutoff + 2);
        if (properties.keepFirstUserMessage()) {
            firstUserMessage(messages, cutoff).ifPresent(compressed::add);
        }
        compressed.add(new UserMessage(SUMMARY_PREFIX + "\n" + summary));
        compressed.addAll(messages.subList(cutoff, messages.size()));
        return compressed;
    }

    /** 取被压缩段里的首条真实用户消息，跳过已有摘要。 */
    private Optional<Message> firstUserMessage(List<Message> messages, int cutoff) {
        for (int index = 0; index < cutoff; index++) {
            Message message = messages.get(index);
            if (message instanceof UserMessage && !String.valueOf(message.getText()).startsWith(SUMMARY_PREFIX)) {
                return Optional.of(message);
            }
        }
        return Optional.empty();
    }

    /** 一次摘要调用的结果；problem 非空表示本次不合格的具体原因。 */
    private record Attempt(String text, String problem) {
    }

    /** 调用摘要模型，输出被截断或格式不符时带着具体原因重试一次。 */
    private String summarize(String transcript) {
        Attempt first = callSummary(transcript, null);
        if (isAcceptable(first.text())) {
            return normalize(first.text());
        }
        String reminder = buildReminder(first);
        log.warn("摘要不合格，重试一次 reason={}", reminder);
        Attempt second = callSummary(transcript, reminder);
        if (isAcceptable(second.text())) {
            return normalize(second.text());
        }
        return null;
    }

    /** 把上次失败的具体原因写进提醒；不携带上次生成内容，避免错误格式被带进重试。 */
    private String buildReminder(Attempt attempt) {
        StringBuilder reminder = new StringBuilder();
        if (attempt.problem() != null) {
            reminder.append(attempt.problem()).append("。");
        } else {
            List<String> missing = REQUIRED_HEADINGS.stream()
                    .filter(heading -> !attempt.text().contains(heading)).toList();
            reminder.append("上次回复缺少标题：").append(String.join("、", missing)).append("。");
        }
        reminder.append("不要调用工具，按模板规定的标题完整输出摘要，不要写开场白或解释。");
        return reminder.toString();
    }

    /** 发起一次摘要调用；输出被截断或调用异常时返回带原因的结果。 */
    private Attempt callSummary(String transcript, String reminder) {
        try {
            List<Message> messages = new ArrayList<>(3);
            messages.add(new SystemMessage(summaryPrompt));
            messages.add(new UserMessage(transcript));
            if (reminder != null) {
                messages.add(new UserMessage(reminder));
            }
            Prompt prompt = summaryOptions == null ? new Prompt(messages) : new Prompt(messages, summaryOptions);
            ChatResponse response = summaryModel.call(prompt);
            if (response == null || response.getResult() == null) {
                return new Attempt(null, "上次摘要没有返回内容");
            }
            String finishReason = response.getResult().getMetadata().getFinishReason();
            if ("length".equalsIgnoreCase(finishReason)) {
                log.warn("摘要输出被截断");
                return new Attempt(null, "上次回复因输出长度上限被截断");
            }
            return new Attempt(response.getResult().getOutput().getText(), null);
        } catch (RuntimeException error) {
            log.warn("摘要模型调用失败 errorType={}", error.getClass().getName());
            return new Attempt(null, "上次摘要调用失败（" + error.getClass().getSimpleName() + "）");
        }
    }

    /** 摘要必须非空且包含模板要求的标题。 */
    private boolean isAcceptable(String summary) {
        if (summary == null || summary.isBlank()) {
            return false;
        }
        return REQUIRED_HEADINGS.stream().allMatch(summary::contains);
    }

    /** 去掉代码围栏和重复前缀。 */
    private String normalize(String summary) {
        String value = summary.strip();
        if (value.startsWith("```")) {
            int firstBreak = value.indexOf('\n');
            if (firstBreak > 0) {
                value = value.substring(firstBreak + 1);
            }
        }
        if (value.endsWith("```")) {
            value = value.substring(0, value.length() - 3).strip();
        }
        if (value.startsWith(SUMMARY_PREFIX)) {
            value = value.substring(SUMMARY_PREFIX.length()).strip();
        }
        return value;
    }
}