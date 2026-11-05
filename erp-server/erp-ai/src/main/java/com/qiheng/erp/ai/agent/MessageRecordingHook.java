package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import org.springframework.ai.chat.messages.Message;
import java.util.List;

/** 模型调用前保存最新的工具结果。 */
@HookPositions({HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
public final class MessageRecordingHook extends MessagesModelHook {
    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        config.metadata("messageRecorder").ifPresent(value -> ((AgentRunRecorder) value).beforeModel(messages));
        return new AgentCommand(null);
    }
    /** 产生模型完成节点，让输出流按顺序读取本轮完整消息。 */
    @Override public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
        return new AgentCommand(null);
    }
    @Override public String getName() { return "message_recording"; }
}
