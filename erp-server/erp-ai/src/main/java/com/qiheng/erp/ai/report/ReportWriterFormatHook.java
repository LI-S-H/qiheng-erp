package com.qiheng.erp.ai.report;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅在报告专家最终输出时校验格式并在同一上下文中修正。 */
@HookPositions(HookPosition.AFTER_MODEL)
public final class ReportWriterFormatHook extends MessagesModelHook {
    public static final String REPAIR_COUNT = "reportWriterFormatRepairCount";
    private static final int MAX_REPAIRS = 3;
    private final ReportWriterOutputConverter converter;

    public ReportWriterFormatHook(ReportWriterOutputConverter converter) {
        this.converter = converter;
    }

    @Override
    public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
        if (messages.isEmpty() || !(messages.getLast() instanceof AssistantMessage assistant)
                || !assistant.getToolCalls().isEmpty()) {
            return new AgentCommand(null);
        }
        AtomicInteger repairs = (AtomicInteger) config.metadata(REPAIR_COUNT).orElseThrow();
        try {
            converter.parse(assistant.getText());
            if (repairs.get() > 0) {
                return new AgentCommand(JumpTo.end, null);
            }
            return new AgentCommand(null);
        } catch (ReportWriterOutputConverter.InvalidOutput error) {
            if (repairs.get() >= MAX_REPAIRS) {
                return new AgentCommand(JumpTo.end, null);
            }
            repairs.incrementAndGet();
            config.metadata(ReportProgress.METADATA_KEY).ifPresent(value -> {
                if (value instanceof ReportProgress progress) {
                    progress.stage("WRITER_FORMAT_RETRY", "报告输出存在格式问题，正在修复。");
                }
            });
            return new AgentCommand(JumpTo.model,
                    List.of(new UserMessage(error.getMessage())), UpdatePolicy.APPEND);
        }
    }

    @Override
    public List<JumpTo> canJumpTo() {
        return List.of(JumpTo.model, JumpTo.end);
    }

    @Override
    public String getName() {
        return "report_writer_format";
    }
}
