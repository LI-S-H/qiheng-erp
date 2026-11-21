package com.qiheng.erp.ai.experience;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.qiheng.erp.ai.agent.context.CompressionTranscript;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 在模型调用前注入经验目录。
 *
 * <p>只注入一段导航摘要，细节由模型调用检索工具获取。目录消息不进入压缩摘要，
 * 每轮按最新摘要重建一次。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
@HookPositions({HookPosition.BEFORE_MODEL})
public final class ExperienceInjectionHook extends MessagesModelHook {

    private static final String SEARCH_HINT = "需要细节时调用 searchExperience(keyword) 查询。";

    private final ExperienceProvider provider;
    private final String agentType;

    /**
     * 为指定智能体建立注入 hook。
     *
     * @param provider 目录摘要来源
     * @param agentType 智能体类型：MAIN / ANALYSIS
     */
    public ExperienceInjectionHook(ExperienceProvider provider, String agentType) {
        this.provider = provider;
        this.agentType = agentType;
    }

    /**
     * 有已发布经验时注入目录，否则保持原样。
     *
     * @param messages 当前消息列表
     * @param config 运行配置
     * @return 注入后的命令
     */
    @Override
    public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
        String summary = provider.summary(agentType);
        if (summary == null || summary.isBlank()) {
            return unchanged();
        }
        String content = CompressionTranscript.EXPERIENCE_PREFIX + "\n"
                + summary.strip() + "\n" + SEARCH_HINT;
        // 第一条已经是同一份目录且没有重复时，不写回状态。
        boolean firstMatches = !messages.isEmpty()
                && messages.getFirst() instanceof UserMessage first
                && content.equals(first.getText());
        boolean duplicates = messages.stream().skip(1).anyMatch(this::isExperience);
        if (firstMatches && !duplicates) {
            return unchanged();
        }
        List<Message> next = new ArrayList<>(messages.size() + 1);
        next.add(new UserMessage(content));
        for (Message message : messages) {
            if (!isExperience(message)) {
                next.add(message);
            }
        }
        return new AgentCommand(next);
    }

    /** 提供本 hook 的名称。 */
    @Override
    public String getName() {
        return "experience_injection";
    }

    /** 排在压缩之后，保证目录不被本轮压缩处理。 */
    @Override
    public int getOrder() {
        return 100;
    }

    /** 判断是否为经验目录消息。 */
    private boolean isExperience(Message message) {
        return message instanceof UserMessage user && user.getText() != null
                && user.getText().startsWith(CompressionTranscript.EXPERIENCE_PREFIX);
    }

    /** 返回不改动状态的命令。 */
    private AgentCommand unchanged() {
        return new AgentCommand((List<Message>) null);
    }
}