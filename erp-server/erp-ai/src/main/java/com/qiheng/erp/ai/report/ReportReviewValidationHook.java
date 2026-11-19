package com.qiheng.erp.ai.report;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.JumpTo;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.alibaba.cloud.ai.graph.agent.hook.messages.UpdatePolicy;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 仅检查审阅最终输出；格式错误时在当前上下文中修正。 */
@HookPositions(HookPosition.AFTER_MODEL)
public final class ReportReviewValidationHook extends MessagesModelHook {
    public static final String REPAIR_COUNT = "reportReviewRepairCount";
    private static final int MAX_REPAIRS = 3;
    private final ObjectMapper mapper;

    public ReportReviewValidationHook(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** 工具调用直接放行，最终输出不合法时反馈模型。 */
    @Override
    public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
        if (messages.isEmpty() || !(messages.getLast() instanceof AssistantMessage assistant)
                || !assistant.getToolCalls().isEmpty()) {
            return new AgentCommand(null);
        }
        AtomicInteger repairs = (AtomicInteger) config.metadata(REPAIR_COUNT).orElseThrow();
        try {
            parse(assistant.getText());
            if (repairs.get() > 0) {
                return new AgentCommand(JumpTo.end, null);
            }
            return new AgentCommand(null);
        } catch (InvalidReviewOutputException error) {
            if (repairs.get() >= MAX_REPAIRS) {
                return new AgentCommand(JumpTo.end, null);
            }
            repairs.incrementAndGet();
            var recorder = ReportTraceHook.recorder(config);
            if (recorder != null) {
                recorder.recordText("FORMAT_FEEDBACK", error.getMessage());
            }
            config.metadata(ReportProgress.METADATA_KEY).ifPresent(value -> {
                if (value instanceof ReportProgress progress) {
                    progress.stage("REVIEW_FORMAT_RETRY", "审阅结果存在格式问题，正在修复。");
                }
            });
            return new AgentCommand(JumpTo.model, List.of(new UserMessage(error.getMessage())), UpdatePolicy.APPEND);
        }
    }

    /** 严格检查字段类型与必要内容；评分和引用判断的矛盾由图处理。 */
    public ReportReview parse(String answer) throws InvalidReviewOutputException {
        final JsonNode root;
        try {
            root = mapper.readTree(answer);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new InvalidReviewOutputException("审核结果 JSON 格式错误，请重新生成。");
        }
        if (root == null || !root.isObject()) {
            throw new InvalidReviewOutputException("审核结果必须是 JSON 对象，请重新生成。");
        }
        if (!root.path("score").isNumber() || !Double.isFinite(root.path("score").doubleValue())
                || root.path("score").doubleValue() < 0 || root.path("score").doubleValue() > 10) {
            throw new InvalidReviewOutputException("审核分数必须为 0 到 10 的数字，请重新生成。");
        }
        if (!root.path("hasReferenceIssue").isBoolean()) {
            throw new InvalidReviewOutputException("hasReferenceIssue 必须为布尔值，请重新生成。");
        }
        if (!root.path("strengths").isArray() || !root.path("issues").isArray()) {
            throw new InvalidReviewOutputException("strengths 和 issues 必须为数组，请重新生成。");
        }
        for (JsonNode strength : root.path("strengths")) {
            if (!strength.isTextual() || strength.asText().isBlank()) {
                throw new InvalidReviewOutputException("审核优点必须是非空文本，请重新生成。");
            }
        }
        for (JsonNode issue : root.path("issues")) {
            for (String key : List.of("problem", "reason", "suggest")) {
                if (!issue.path(key).isTextual() || issue.path(key).asText().isBlank()) {
                    throw new InvalidReviewOutputException("审核问题缺少非空字段 " + key + "，请重新生成。");
                }
            }
        }
        if (root.path("score").doubleValue() < 8 && root.path("issues").isEmpty()) {
            throw new InvalidReviewOutputException("审核低于 8 分时必须说明问题，请重新生成。");
        }
        try {
            return mapper.treeToValue(root, ReportReview.class);
        } catch (JsonProcessingException error) {
            throw new InvalidReviewOutputException("审核结果字段格式错误，请重新生成。");
        }
    }

    @Override
    public List<JumpTo> canJumpTo() {
        return List.of(JumpTo.model, JumpTo.end);
    }

    /** afterModel 逆序执行，先记录模型原文再决定是否跳转。 */
    @Override
    public int getOrder() {
        return -1;
    }

    @Override
    public String getName() {
        return "report_review_validation";
    }

    /** 格式错误与模型调用及资料读取失败区分。 */
    public static final class InvalidReviewOutputException extends IOException {
        public InvalidReviewOutputException(String message) {
            super(message);
        }
    }
}
