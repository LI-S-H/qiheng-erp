package com.qiheng.erp.ai.memory.summary;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.TokenCounter;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.summarization.SummarizationHook;
import com.alibaba.cloud.ai.graph.state.ReplaceAllWith;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 节点和 Hook 共用的无状态压缩器；调用方决定把返回的消息写回哪一个 State 字段。 */
public final class ContextCompressor {

    private final SummaryChatModel model;
    private final int trigger;
    private final int messagesToKeep;
    private final TokenCounter counter = TokenCounter.approximateMsgCounter(2);

    public ContextCompressor(ChatModel model, int trigger, int messagesToKeep) {
        this(new SummaryChatModel(model, 50000, 0.10, 50000, Duration.ofMinutes(5)),
                trigger, messagesToKeep);
    }

    public ContextCompressor(SummaryChatModel model, int trigger, int messagesToKeep) {
        if (model == null || trigger <= 0 || messagesToKeep < 1) {
            throw new IllegalArgumentException("摘要压缩配置不合法");
        }
        this.model = model;
        this.trigger = trigger;
        this.messagesToKeep = messagesToKeep;
    }

    public List<Message> compact(List<Message> messages) {
        return compact(messages, RunnableConfig.builder().build());
    }

    /**
     * 是否达到压缩条件：token 超过阈值，且确实有可压缩的空间。
     *
     * <p>把"要不要压"暴露给调用方，压缩器本身就不必知道进度、事件这些事；
     * 调用方据此在压缩前后推送状态。</p>
     *
     * @param messages 待压缩消息
     * @return true 表示下一次 {@link #compact(List)} 会真正调用摘要模型
     */
    public boolean needsCompaction(List<Message> messages) {
        if (messages == null || messages.size() <= 1) {
            return false;
        }
        return counter.countTokens(messages) >= trigger && keepCount(messages) > 0;
    }

    public List<Message> compact(List<Message> messages, RunnableConfig config) {
        if (messages == null || config == null) {
            throw new IllegalArgumentException("消息和运行配置不能为空");
        }
        // 判定只看 token：消息条数少但单条巨大的历史同样要压，否则一次超大输出就能撑爆上下文。
        // 只剩一条时没有可压缩的空间（那一条就是本轮自己的内容）。
        if (!needsCompaction(messages)) {
            return messages;
        }
        int keep = keepCount(messages);
        if (keep < 1) {
            return messages;
        }

        IdentityHashMap<Message, Message> originals = new IdentityHashMap<>();
        List<Message> projected = new ArrayList<>(messages.size());
        for (Message original : messages) {
            Message projection = original;
            if (original instanceof ToolResponseMessage tool) {
                projection = new SummaryToolMessage(tool);
            } else if (original instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
                StringBuilder text = new StringBuilder(assistant.getText() == null ? "" : assistant.getText());
                assistant.getToolCalls().forEach(call -> text.append("\n工具调用 id=").append(call.id())
                        .append(" name=").append(call.name()).append(" arguments=").append(call.arguments()));
                projection = AssistantMessage.builder().content(text.toString()).properties(assistant.getMetadata())
                        .toolCalls(assistant.getToolCalls()).media(assistant.getMedia()).build();
            }
            originals.put(projection, original);
            projected.add(projection);
        }
        // 官方 Hook 会把模型异常转成摘要文本，必须额外记录本次真实异常，才能安全保留原文。
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        ChatModel guarded = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                try {
                    return model.call(prompt);
                } catch (RuntimeException error) {
                    failure.set(error);
                    throw error;
                }
            }
        };
        SummarizationHook delegate = SummarizationHook.builder()
                .model(guarded)
                .maxTokensBeforeSummary(trigger)
                .messagesToKeep(keep)
                .keepFirstUserMessage(false)
                .tokenCounter(counter)
                .summaryPrompt("%s")
                .build();
        Map<String, Object> update;
        try {
            // 使用公开 Action 桥接获取 REPLACE 更新，避免依赖 AgentCommand 的包级访问器。
            update = MessagesModelHook.beforeModelAction(delegate)
                    .apply(new OverAllState(Map.of("messages", projected)), config).join();
        } catch (RuntimeException error) {
            return messages;
        }
        if (failure.get() != null || !(update.get("messages") instanceof ReplaceAllWith<?> replacement)) {
            return messages;
        }
        @SuppressWarnings("unchecked")
        List<Message> updated = (List<Message>) replacement.newValues();
        boolean unchanged = updated.size() == projected.size();
        for (int i = 0; unchanged && i < updated.size(); i++) {
            unchanged = updated.get(i) == projected.get(i);
        }
        if (unchanged) {
            return messages;
        }
        // 最近消息恢复为原始对象，不把用于摘要的工具文字投影写回工作上下文。
        List<Message> restored = new ArrayList<>(updated.size());
        for (Message message : updated) {
            restored.add(originals.getOrDefault(message, message));
        }
        return restored;
    }

    /**
     * 保留最近消息的条数：先受条数上限约束，再受「最近窗口预算」约束。
     *
     * <p>只按条数保留会漏掉"消息少但单条巨大"的历史——把条数门槛去掉后，
     * 仍然需要按 token 收缩保留窗口，否则压完还是超阈值。至少保留 1 条：
     * 本轮自己的输入与输出不能被摘要掉。</p>
     *
     * @param messages 待压缩消息
     * @return 保留条数；0 表示没有可压缩的空间
     */
    private int keepCount(List<Message> messages) {
        int limit = Math.min(messagesToKeep, messages.size() - 1);
        if (limit < 1) {
            return 0;
        }
        int budget = Math.max(1, trigger / 2);
        int count = 0;
        int tokens = 0;
        for (int i = messages.size() - 1; i >= 0 && count < limit; i--) {
            tokens += counter.countTokens(List.of(messages.get(i)));
            if (count > 0 && tokens > budget) {
                break;
            }
            count++;
        }
        return count;
    }

    /**
     * 通知压缩状态；回调缺省时静默跳过。
     */
    private static final class SummaryToolMessage extends ToolResponseMessage {

        private final String summaryText;

        private SummaryToolMessage(ToolResponseMessage original) {
            super(original.getResponses(), original.getMetadata());
            StringBuilder text = new StringBuilder();
            original.getResponses().forEach(response -> text.append("工具结果 id=").append(response.id())
                    .append(" name=").append(response.name()).append(" result=")
                    .append(response.responseData()).append('\n'));
            summaryText = text.toString();
        }

        @Override
        public String getText() {
            return summaryText;
        }
    }
}