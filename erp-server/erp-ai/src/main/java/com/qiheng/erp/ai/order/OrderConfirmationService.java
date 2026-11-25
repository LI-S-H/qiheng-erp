package com.qiheng.erp.ai.order;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.action.PurchaseOrderActionReceipt;
import com.qiheng.erp.ai.agent.MainAgentService;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.draft.PurchaseDraftReceipt;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.sales.SalesOrderActionReceipt;
import com.qiheng.erp.ai.sales.SalesOrderDraftReceipt;
import com.qiheng.erp.ai.sql.scope.PermissionCodes;
import com.qiheng.erp.security.service.LoginUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import reactor.core.publisher.Flux;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 单据确认的通用入口：校验检查点、选择策略、写回执并恢复运行。
 *
 * @author Li
 * @since 2026-11-25
 */
public final class OrderConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmationService.class);

    /** 恢复通道与图执行必须使用同一个运行标识。 */
    public record ResumedRun(String runId, Flux<NodeOutput> outputs) {
    }

    private static final String HITL_NODE_PREFIX = "_AGENT_HOOK_HITL";
    private static final String ACTION_CREATE = "CREATE";
    private static final String ACTION_UPDATE = "UPDATE";
    private static final String ACTION_SUBMIT = "SUBMIT";
    private static final String ACTION_CANCEL = "CANCEL";

    private static final Set<String> CREATE_STATUSES = Set.of("created", "edited", "rejected", "failed");
    private static final Set<String> UPDATE_STATUSES = Set.of("updated", "edited", "rejected", "failed");
    private static final Set<String> SUBMIT_STATUSES = Set.of("submitted", "rejected", "failed");
    private static final Set<String> CANCEL_STATUSES = Set.of("cancelled", "rejected", "failed");

    private final ReactAgent agent;
    private final RedisSaver saver;
    private final MainAgentService runs;
    private final AiConversationMapper conversations;
    private final LoginUserService users;
    private final ObjectMapper mapper;
    private final ConversationMessageStore messages;
    private final List<OrderConfirmationStrategy> strategies;

    /**
     * 注入图、检查点与会话校验依赖。
     *
     * @param agent 主智能体图
     * @param saver 检查点存储
     * @param runs 主智能体运行入口
     * @param conversations 会话数据访问
     * @param users 登录用户查询
     * @param mapper JSON 序列化组件
     * @param messages 会话消息存储
     * @param strategies 单据确认策略
     */
    public OrderConfirmationService(ReactAgent agent, RedisSaver saver, MainAgentService runs,
                                    AiConversationMapper conversations, LoginUserService users,
                                    ObjectMapper mapper, ConversationMessageStore messages,
                                    List<OrderConfirmationStrategy> strategies) {
        this.agent = agent;
        this.saver = saver;
        this.runs = runs;
        this.conversations = conversations;
        this.users = users;
        this.mapper = mapper;
        this.messages = messages;
        this.strategies = List.copyOf(strategies);
    }

    /**
     * 提交草稿处置结果并启动恢复运行。
     *
     * @param userId 当前用户
     * @param conversationId 会话编号
     * @param toolCallId 待确认的工具调用编号
     * @param receipt 具体草稿回执
     * @return 恢复运行标识及图事件流
     * @throws Exception 校验或写入失败
     */
    public ResumedRun resolveDraftForUser(long userId, long conversationId, String toolCallId,
                                          Object receipt) throws Exception {
        return resolve(userId, conversationId, toolCallId, receipt, true);
    }

    /**
     * 提交提交/取消动作的处置结果并启动恢复运行。
     *
     * @param userId 当前用户
     * @param conversationId 会话编号
     * @param toolCallId 待确认的工具调用编号
     * @param receipt 具体动作回执
     * @return 恢复运行标识及图事件流
     * @throws Exception 校验或写入失败
     */
    public ResumedRun resolveActionForUser(long userId, long conversationId, String toolCallId,
                                           Object receipt) throws Exception {
        return resolve(userId, conversationId, toolCallId, receipt, false);
    }

    /** 草稿与动作共用同一套锁内恢复流程。 */
    private ResumedRun resolve(long userId, long conversationId, String toolCallId, Object receipt,
                               boolean draft) throws Exception {
        requireAccess(userId, conversationId);
        RunnableConfig config = configOf(userId, conversationId);
        Checkpoint checkpoint = saver.get(config)
                .orElseThrow(MainAgentService.ConfirmationExpiredException::new);
        AssistantMessage.ToolCall pendingCall = requirePendingCall(checkpoint, toolCallId);
        OrderConfirmationStrategy strategy = strategyOf(pendingCall.name());
        String action = OrderToolNames.actionOf(pendingCall.name());
        requireAllowedStatus(action, statusOf(receipt), draft);

        String runId = runs.currentRunIdForUser(userId, conversationId);
        if (runId == null) {
            runId = UUID.randomUUID().toString();
        }
        String resumedRunId = runId;
        Flux<NodeOutput> outputs = runs.resumeAfterConfirmation(userId, conversationId, runId,
                (runConfig, lockedCheckpoint) -> {
                    AssistantMessage.ToolCall call = requirePendingCall(lockedCheckpoint, toolCallId);
                    Map<String, Object> toolDraft = draft ? parseArguments(call.arguments()) : Map.of();
                    // 用户改动以卡片打开时的参数为基线；旧回执没有基线时退回模型参数。
                    Map<String, Object> baseline = draft ? originalParamsOf(receipt, toolDraft) : Map.of();
                    String modelResult;
                    Map<String, Object> displayRecord;
                    if (draft) {
                        OrderDraftConfirmation confirmation = strategy.draftConfirmation(receipt, action,
                                baseline);
                        modelResult = OrderConfirmationTexts.draftText(confirmation, mapper);
                        displayRecord = OrderConfirmationTexts.draftRecord(confirmation);
                    } else {
                        OrderActionConfirmation confirmation = strategy.actionConfirmation(receipt, action);
                        modelResult = OrderConfirmationTexts.actionText(confirmation, mapper);
                        displayRecord = OrderConfirmationTexts.actionRecord(confirmation);
                    }
                    writeToolResponse(runConfig, lockedCheckpoint, call, modelResult);
                    recordResult(userId, conversationId, resumedRunId, call, displayRecord);
                });
        return new ResumedRun(runId, outputs);
    }

    /** 把给模型的结论文本写入检查点。 */
    private void writeToolResponse(RunnableConfig config, Checkpoint checkpoint,
                                   AssistantMessage.ToolCall call, String modelResult) throws Exception {
        ToolResponseMessage response = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), modelResult)))
                .build();
        agent.getAndCompileGraph().updateState(
                RunnableConfig.builder(config).checkPointId(checkpoint.getId()).build(),
                Map.of("messages", List.of(response)));
    }

    /** 保存一条工具结果，供历史卡片展示；失败不影响已经完成的确认。 */
    private void recordResult(long userId, long conversationId, String runId,
                              AssistantMessage.ToolCall call, Map<String, Object> displayRecord) {
        try {
            Map<String, Object> content = new LinkedHashMap<>();
            content.put("callId", call.id());
            content.put("name", call.name());
            content.put("action", OrderToolNames.actionOf(call.name()));
            String orderType = OrderToolNames.orderTypeOf(call.name());
            if (orderType != null) {
                content.put("orderType", orderType);
            }
            content.put("result", mapper.writeValueAsString(displayRecord));
            messages.append(new AiMessage()
                    .setUserId(userId)
                    .setConversationId(conversationId)
                    .setRequestId(runId)
                    .setMessageKey(runId + ":tool-result:" + call.id())
                    .setRole("assistant")
                    .setKind("TOOL_RESULT")
                    .setContent(mapper.writeValueAsString(content)));
        } catch (Exception error) {
            log.warn("单据确认记录写入失败 runId={} callId={}", runId, call.id(), error);
        }
    }

    /** 用户改动以卡片打开时的参数为基线；旧回执没有基线时退回模型参数。 */
    private Map<String, Object> originalParamsOf(Object receipt, Map<String, Object> fallback) {
        if (receipt instanceof PurchaseDraftReceipt value && value.originalParams() != null) {
            return value.originalParams();
        }
        if (receipt instanceof SalesOrderDraftReceipt value && value.originalParams() != null) {
            return value.originalParams();
        }
        return fallback;
    }

    /** 按工具名选择策略。 */
    private OrderConfirmationStrategy strategyOf(String toolName) {
        return strategies.stream()
                .filter(strategy -> strategy.supports(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("不支持的确认工具：" + toolName));
    }

    /** 读取回执里的处置状态。 */
    private String statusOf(Object receipt) {
        if (receipt instanceof SalesOrderDraftReceipt value) {
            return value.status();
        }
        if (receipt instanceof SalesOrderActionReceipt value) {
            return value.status();
        }
        if (receipt instanceof PurchaseDraftReceipt value) {
            return value.status();
        }
        if (receipt instanceof PurchaseOrderActionReceipt value) {
            return value.status();
        }
        throw new IllegalArgumentException("不支持的确认回执");
    }

    /** 校验处置状态与工具动作是否匹配。 */
    private void requireAllowedStatus(String action, String status, boolean draft) {
        if (status == null) {
            throw new IllegalArgumentException("请提供处置结果");
        }
        boolean allowed = switch (action) {
            case ACTION_CREATE -> draft && CREATE_STATUSES.contains(status);
            case ACTION_UPDATE -> draft && UPDATE_STATUSES.contains(status);
            case ACTION_SUBMIT -> !draft && SUBMIT_STATUSES.contains(status);
            case ACTION_CANCEL -> !draft && CANCEL_STATUSES.contains(status);
            default -> false;
        };
        if (!allowed) {
            throw new IllegalArgumentException("处置状态与工具动作不匹配：" + status);
        }
    }

    /** 读取待确认的工具调用；检查点已推进或编号不匹配时视为确认失效。 */
    private AssistantMessage.ToolCall requirePendingCall(Checkpoint checkpoint, String toolCallId) {
        if (checkpoint.getNextNodeId() == null || !checkpoint.getNextNodeId().startsWith(HITL_NODE_PREFIX)) {
            throw new MainAgentService.ConfirmationExpiredException();
        }
        Object rawMessages = checkpoint.getState().get("messages");
        if (!(rawMessages instanceof List<?> messages) || messages.isEmpty()
                || !(messages.getLast() instanceof AssistantMessage assistant)) {
            throw new MainAgentService.ConfirmationExpiredException();
        }
        return assistant.getToolCalls().stream()
                .filter(item -> item.id().equals(toolCallId))
                .findFirst()
                .orElseThrow(MainAgentService.ConfirmationExpiredException::new);
    }

    /** 解析模型生成的工具参数；兼容框架包一层 request 的结构。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseArguments(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            var root = mapper.readTree(raw);
            var node = root != null && root.isObject() && root.path("request").isObject()
                    ? root.path("request") : root;
            if (node == null || !node.isObject()) {
                return Map.of();
            }
            return mapper.convertValue(node, Map.class);
        } catch (Exception error) {
            log.warn("确认参数解析失败 raw={}", raw, error);
            return Map.of();
        }
    }

    /** 校验会话归属与 AI 使用权限。 */
    private void requireAccess(long userId, long conversationId) {
        if (conversations.selectOwned(conversationId, userId) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        var user = users.findByUserId(userId);
        var permissions = PermissionCodes.of(user);
        if (user == null || (!permissions.contains("*") && !permissions.contains("ai:assistant:use"))) {
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