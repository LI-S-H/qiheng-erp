package com.qiheng.erp.ai.clarification;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.MainAgentService;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.sql.scope.PermissionCodes;
import com.qiheng.erp.security.service.LoginUserService;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 追问答案的检查点入口：校验答案、写回工具结果并恢复运行。
 *
 * @author Li
 * @since 2026-11-26
 */
public final class ClarificationConfirmationService {

    /** 恢复通道与图执行必须使用同一个运行标识。 */
    public record ResumedRun(String runId, Flux<NodeOutput> outputs) {
    }

    private static final String HITL_NODE_PREFIX = "_AGENT_HOOK_HITL";

    private final ReactAgent agent;
    private final RedisSaver saver;
    private final MainAgentService runs;
    private final AiConversationMapper conversations;
    private final LoginUserService users;
    private final ObjectMapper mapper;

    /**
     * 注入图、检查点与会话校验依赖。
     *
     * @param agent 主智能体图
     * @param saver 检查点存储
     * @param runs 主智能体运行入口
     * @param conversations 会话数据访问
     * @param users 登录用户查询
     * @param mapper JSON 序列化组件
     */
    public ClarificationConfirmationService(ReactAgent agent, RedisSaver saver, MainAgentService runs,
                                            AiConversationMapper conversations, LoginUserService users,
                                            ObjectMapper mapper) {
        this.agent = agent;
        this.saver = saver;
        this.runs = runs;
        this.conversations = conversations;
        this.users = users;
        this.mapper = mapper;
    }

    /**
     * 提交用户对当前追问的回答。
     *
     * @param userId 当前用户
     * @param conversationId 会话编号
     * @param questionRequestId 追问编号，即工具调用编号
     * @param answers 用户回答
     * @param note 其他补充
     * @return 恢复运行标识及事件流
     * @throws Exception 校验或写入失败
     */
    public ResumedRun resolveForUser(long userId, long conversationId, String questionRequestId,
                                     List<ClarificationAnswer> answers, String note) throws Exception {
        requireAccess(userId, conversationId);
        if (note != null && note.length() > ClarificationRules.MAX_ANSWER_LENGTH) {
            throw new IllegalArgumentException("补充说明不能超过 " + ClarificationRules.MAX_ANSWER_LENGTH + " 个字符");
        }
        RunnableConfig config = configOf(userId, conversationId);
        Checkpoint checkpoint = saver.get(config)
                .orElseThrow(MainAgentService.ConfirmationExpiredException::new);
        AssistantMessage.ToolCall call = requirePendingCall(checkpoint, questionRequestId);
        List<AskUserTool.Question> questions = parseQuestions(call);
        List<ClarificationRules.FieldError> errors = ClarificationRules.validateAnswers(questions, answers);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("回答不符合要求：" + ClarificationRules.summarizeForUser(errors));
        }

        String runId = runs.currentRunIdForUser(userId, conversationId);
        if (runId == null) {
            runId = UUID.randomUUID().toString();
        }
        String result = resultJson(answers, note);
        String resumedRunId = runId;
        Flux<NodeOutput> outputs = runs.resumeAfterConfirmation(userId, conversationId, runId,
                (runConfig, lockedCheckpoint) -> {
                    AssistantMessage.ToolCall lockedCall = requirePendingCall(lockedCheckpoint, questionRequestId);
                    writeToolResponse(runConfig, lockedCheckpoint, lockedCall, result);
                });
        return new ResumedRun(resumedRunId, outputs);
    }

    /** 把答案写成与工具调用配对的 ToolResponseMessage。 */
    private void writeToolResponse(RunnableConfig config, Checkpoint checkpoint, AssistantMessage.ToolCall call,
                                   String result) throws Exception {
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), result)))
                .build();
        agent.getAndCompileGraph().updateState(
                RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build(),
                Map.of("messages", List.of(response)));
    }

    /** 从检查点参数解析问题；HITL 前已校验，正常不会失败。 */
    private List<AskUserTool.Question> parseQuestions(AssistantMessage.ToolCall call) {
        ClarificationRules.QuestionValidation validation =
                ClarificationRules.validateQuestions(call.arguments(), mapper);
        if (!validation.valid()) {
            throw new IllegalStateException("当前追问参数无效，请重新发起：" + ClarificationRules.summarize(validation.errors()));
        }
        return validation.questions();
    }

    /** 组装交给模型的结果 JSON。 */
    private String resultJson(List<ClarificationAnswer> answers, String note) throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("answers", answers);
        if (note != null && !note.isBlank()) {
            body.put("note", note);
        }
        return mapper.writeValueAsString(body);
    }

    /** 读取待回答的追问调用；检查点已推进或编号不匹配时视为确认失效。 */
    private AssistantMessage.ToolCall requirePendingCall(Checkpoint checkpoint, String questionRequestId) {
        if (checkpoint.getNextNodeId() == null || !checkpoint.getNextNodeId().startsWith(HITL_NODE_PREFIX)) {
            throw new MainAgentService.ConfirmationExpiredException();
        }
        Object rawMessages = checkpoint.getState().get("messages");
        if (!(rawMessages instanceof List<?> messages) || messages.isEmpty()
                || !(messages.getLast() instanceof AssistantMessage assistant)) {
            throw new MainAgentService.ConfirmationExpiredException();
        }
        AssistantMessage.ToolCall call = assistant.getToolCalls().stream()
                .filter(item -> item.id().equals(questionRequestId))
                .findFirst()
                .orElseThrow(MainAgentService.ConfirmationExpiredException::new);
        if (!AskUserTool.TOOL_NAME.equals(call.name())) {
            throw new MainAgentService.ConfirmationExpiredException();
        }
        return call;
    }

    /** 校验会话归属与 AI 使用权限。 */
    private void requireAccess(long userId, long conversationId) {
        if (conversations.selectOwned(conversationId, userId) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        var user = users.findByUserId(userId);
        if (user == null) {
            throw new IllegalStateException("当前用户无 AI 使用权限");
        }
        var permissions = PermissionCodes.of(user);
        if (!permissions.contains("*") && !permissions.contains("ai:assistant:use")) {
            throw new IllegalStateException("当前用户无 AI 使用权限");
        }
    }

    /** 组装与主智能体一致的运行配置。 */
    private RunnableConfig configOf(long userId, long conversationId) {
        String businessDate = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
        return RunnableConfig.builder().threadId("main:" + userId + ":" + conversationId)
                .addMetadata("businessDate", businessDate)
                .addMetadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT,
                        new AgentToolContext(userId, conversationId, businessDate))
                .build();
    }
}