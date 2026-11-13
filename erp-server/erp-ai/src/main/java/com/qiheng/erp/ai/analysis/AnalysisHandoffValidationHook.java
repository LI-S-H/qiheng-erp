package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 在分析循环中检查最终交接格式；普通工具调用继续原流程。
 *
 * @author Li
 * @since 2026-11-10
 */
@HookPositions(HookPosition.AFTER_MODEL)
final class AnalysisHandoffValidationHook extends MessagesModelHook {
    static final String REPAIR_COUNT = "analysisHandoffRepairCount";
    private static final int MAX_REPAIRS = 3;
    private final ObjectMapper mapper;

    /** 注入 JSON 解析组件，用于校验最终交接内容。 */
    AnalysisHandoffValidationHook(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 仅检查无工具调用的最终答复；错误时保留上下文并回到模型。 */
    @Override
    public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
        if (messages.isEmpty() || !(messages.getLast() instanceof AssistantMessage assistant)
                || !assistant.getToolCalls().isEmpty()) {
            return new AgentCommand(null);
        }
        var repairs = (AtomicInteger) config.metadata(REPAIR_COUNT).orElseThrow();
        try {
            parse(assistant.getText());
            if (repairs.get() > 0) {
                // 修正后明确结束，避免框架沿用上次跳回模型的状态。
                return new AgentCommand(JumpTo.end, null);
            }
            return new AgentCommand(null);
        } catch (InvalidHandoffException error) {
            if (repairs.get() >= MAX_REPAIRS) {
                // 保留最后一次答复，由服务层统一返回格式失败及已有结果。
                return new AgentCommand(JumpTo.end, null);
            }
            repairs.incrementAndGet();
            return new AgentCommand(JumpTo.model, List.of(new UserMessage(error.getMessage())), UpdatePolicy.APPEND);
        }
    }

    /**
     * 同一套规则供 Hook 和服务层使用，防止最终交付与循环判断不一致。
     *
     * @param answer 模型给出的最终答复
     * @return 校验通过的交接结果
     * @throws InvalidHandoffException 交接内容不符合约定格式
     */
    AnalysisHandoff parse(String answer) throws InvalidHandoffException {
        final AnalysisHandoff handoff;
        try {
            // 结果后还有多余字符时也必须修正，不能只解析前面的合法对象。
            handoff = mapper.readerFor(AnalysisHandoff.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(answer);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new InvalidHandoffException("最终交接结果的 JSON 格式错误，请重新生成。");
        }
        if (handoff == null || handoff.completion() == null
                || !List.of("COMPLETED", "PARTIAL", "NEED_INFO").contains(handoff.completion())) {
            throw new InvalidHandoffException("交接结果格式错误：completion 无效，请重新生成。");
        }
        if (handoff.summary() == null || handoff.summary().isBlank()) {
            throw new InvalidHandoffException("交接结果格式错误：缺少 summary，请重新生成。");
        }
        return handoff;
    }

    /** 只允许返回模型或结束，保证框架构建相应跳转路径。 */
    @Override
    public List<JumpTo> canJumpTo() {
        return List.of(JumpTo.model, JumpTo.end);
    }

    /** afterModel 逆序执行，确保普通流水先于最终格式检查。 */
    @Override
    public int getOrder() {
        return -1;
    }

    /** 提供本 Hook 的名称。 */
    @Override
    public String getName() {
        return "analysis_handoff_validation";
    }

    /** 与文件读写错误区分，便于服务层返回准确失败原因。 */
    static final class InvalidHandoffException extends IOException {
        InvalidHandoffException(String message) {
            super(message);
        }
    }
}
