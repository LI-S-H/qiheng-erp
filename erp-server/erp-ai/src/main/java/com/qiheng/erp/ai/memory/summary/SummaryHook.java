package com.qiheng.erp.ai.memory.summary;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import org.springframework.ai.chat.messages.Message;

import java.util.List;
import java.util.Objects;

/** Agent 场景的薄适配；普通图节点可以直接使用同一个 ContextCompressor。 */
@HookPositions({HookPosition.BEFORE_MODEL})
public final class SummaryHook extends MessagesModelHook {

    private final ContextCompressor compressor;

    public SummaryHook(ContextCompressor compressor) {
        this.compressor = Objects.requireNonNull(compressor, "摘要压缩器不能为空");
    }

    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        List<Message> compacted = compressor.compact(messages, config);
        if (compacted == messages) {
            return new AgentCommand(null);
        }
        return new AgentCommand(compacted, UpdatePolicy.REPLACE);
    }

    @Override
    public String getName() {
        return "erp_summary";
    }
}
