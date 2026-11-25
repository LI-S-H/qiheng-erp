package com.qiheng.erp.ai.sales;

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
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.order.OrderToolNames;
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
 * 销售单确认结果的恢复入口：把用户处置写成工具结果，再让图从人工确认节点继续。
 *
 * @author Li
 * @since 2026-11-24
 */
public final class SalesOrderConfirmationService {

    /** 恢复通道与图执行必须使用同一个运行标识。 */
    public record ResumedRun(String runId, Flux<NodeOutput> outputs) {}

    /** 人工确认节点前缀，与主智能体保持一致。 */
    private static final String HITL_NODE_PREFIX = "_AGENT_HOOK_HITL";

    private final ReactAgent agent;

    private final RedisSaver saver;

    private final MainAgentService runs;

    private final AiConversationMapper conversations;

    private final LoginUserService users;

    private final ObjectMapper mapper;

    private final ConversationMessageStore messages;

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
     */
    public SalesOrderConfirmationService(ReactAgent agent, RedisSaver saver, MainAgentService runs,
                                         AiConversationMapper conversations, LoginUserService users,
                                         ObjectMapper mapper, ConversationMessageStore messages) {
        this.agent = agent;
        this.saver = saver;
        this.runs = runs;
        this.conversations = conversations;
        this.users = users;
        this.mapper = mapper;
        this.messages = messages;
    }

    /**
     * 提交用户处置，并把恢复后的图事件流交给调用方订阅。
     *
     * @param userId 当前用户
     * @param conversationId 会话编号
     * @param toolCallId 待确认的工具调用编号
     * @param receipt 用户处置结果
     * @return 恢复运行标识及图事件流
     */
    public ResumedRun resolveForUser(long userId, long conversationId, String toolCallId,
                                           SalesOrderDraftReceipt receipt) throws Exception {
        return resolveReceipt(userId, conversationId, toolCallId, payload(receipt));
    }

    /**
     * 提交用户对提交或取消动作的处置结果，并启动恢复运行。
     *
     * @param userId 当前用户
     * @param conversationId 会话编号
     * @param toolCallId 待确认的工具调用编号
     * @param receipt 用户处置结果
     * @return 恢复运行标识及图事件流
     */
    public ResumedRun resolveActionForUser(long userId, long conversationId, String toolCallId,
                                                 SalesOrderActionReceipt receipt) throws Exception {
        return resolveReceipt(userId, conversationId, toolCallId, actionPayload(receipt));
    }

    /** 校验待确认调用、写入工具结果并恢复运行；草稿与动作共用。 */
    private ResumedRun resolveReceipt(long userId, long conversationId, String toolCallId,
                                            String payload) throws Exception {
        requireAccess(userId, conversationId);
        // 只读快检：常见失效直接返回接口错误，避免用户等后台运行才发现。
        var config = configOf(userId, conversationId);
        requirePendingCall(saver.get(config).orElseThrow(MainAgentService.ConfirmationExpiredException::new),
                toolCallId);
        // 恢复运行沿用原轮次，历史消息不会被拆成两段。
        String runId = runs.currentRunIdForUser(userId, conversationId);
        if (runId == null) {
            runId = UUID.randomUUID().toString();
        }
        String resumedRunId = runId;
        var outputs = runs.resumeAfterConfirmation(userId, conversationId, runId, (runConfig, checkpoint) -> {
            // 锁内二次校验，避免快检之后确认已被其他请求处理。
            var call = requirePendingCall(checkpoint, toolCallId);
            agent.getAndCompileGraph().updateState(
                    RunnableConfig.builder(runConfig).checkPointId(checkpoint.getId()).build(),
                    Map.of("messages", List.of(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(call.id(), call.name(), payload)))
                            .build())));
            recordResult(userId, conversationId, resumedRunId, call, payload);
        });
        return new ResumedRun(runId, outputs);
    }

    /** 读取待确认的工具调用；检查点已推进或调用编号不匹配时视为确认失效。 */
    private AssistantMessage.ToolCall requirePendingCall(Checkpoint checkpoint, String toolCallId) {
        if (checkpoint.getNextNodeId() == null || !checkpoint.getNextNodeId().startsWith(HITL_NODE_PREFIX)) {
            throw new MainAgentService.ConfirmationExpiredException();
        }
        var messages = (List<?>) checkpoint.getState().get("messages");
        if (messages == null || messages.isEmpty()
                || !(messages.getLast() instanceof AssistantMessage assistant)) {
            throw new MainAgentService.ConfirmationExpiredException();
        }
        return assistant.getToolCalls().stream()
                .filter(item -> item.id().equals(toolCallId))
                .findFirst()
                .orElseThrow(MainAgentService.ConfirmationExpiredException::new);
    }

    /** 保存一条工具结果，供历史回看；失败不影响已经完成的确认。 */
    private void recordResult(long userId, long conversationId, String runId,
                              AssistantMessage.ToolCall call, String payload) {
        try {
            var content = new LinkedHashMap<String, Object>();
            content.put("callId", call.id());
            content.put("name", call.name());
            content.put("action", actionOf(call.name()));
            content.put("orderType", "SALES");
            content.put("result", payload);
            messages.append(new AiMessage()
                    .setUserId(userId)
                    .setConversationId(conversationId)
                    .setRequestId(runId)
                    .setMessageKey(runId + ":tool-result:" + call.id())
                    .setRole("assistant")
                    .setKind("TOOL_RESULT")
                    .setContent(mapper.writeValueAsString(content)));
        } catch (Exception error) {
            // 结果记录失败不影响用户已经完成的确认。
        }
    }

    /** 工具名对应的动作。 */
    private String actionOf(String name) {
        return OrderToolNames.actionOf(name);
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

    /** 把处置结果转成模型可直接理解的结构化结论。 */
    private String payload(SalesOrderDraftReceipt receipt) {
        if (receipt == null || receipt.status() == null) {
            throw new IllegalArgumentException("请提供处置结果");
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("status", receipt.status());
        data.put("message", conclusion(receipt));
        if (receipt.salesOrderId() != null) {
            data.put("salesOrderId", receipt.salesOrderId());
        }
        if (receipt.orderNo() != null) {
            data.put("orderNo", receipt.orderNo());
        }
        if (receipt.orderStatus() != null) {
            data.put("orderStatus", receipt.orderStatus());
        }
        if (receipt.error() != null) {
            data.put("error", receipt.error());
        }
        if (receipt.finalParams() != null) {
            data.put("finalParams", receipt.finalParams());
        }
        if (receipt.reasons() != null && !receipt.reasons().isEmpty()) {
            data.put("reasons", receipt.reasons());
        }
        if (receipt.suggestion() != null) {
            data.put("suggestion", receipt.suggestion());
        }
        if (receipt.userNote() != null) {
            data.put("userNote", receipt.userNote());
        }
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("销售单确认结果序列化失败", error);
        }
    }

    /** 把提交或取消的处置结果转成模型可读的结构化结论。 */
    private String actionPayload(SalesOrderActionReceipt receipt) {
        if (receipt == null || receipt.status() == null) {
            throw new IllegalArgumentException("请提供处置结果");
        }
        var data = new LinkedHashMap<String, Object>();
        data.put("status", receipt.status());
        data.put("message", actionConclusion(receipt.status()));
        if (receipt.salesOrderId() != null) {
            data.put("orderId", receipt.salesOrderId());
            data.put("salesOrderId", receipt.salesOrderId());
        }
        if (receipt.orderNo() != null) {
            data.put("orderNo", receipt.orderNo());
        }
        if (receipt.orderStatus() != null) {
            data.put("orderStatus", receipt.orderStatus());
        }
        if (receipt.error() != null) {
            data.put("error", receipt.error());
        }
        if (receipt.reason() != null) {
            data.put("reason", receipt.reason());
        }
        if (receipt.deliveryDate() != null) {
            data.put("deliveryDate", receipt.deliveryDate());
        }
        try {
            return mapper.writeValueAsString(data);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("销售单动作结果序列化失败", error);
        }
    }

    /** 生成提交或取消给模型看的结论文字。 */
    private String actionConclusion(String status) {
        return switch (status) {
            case SalesOrderActionReceipt.STATUS_SUBMITTED -> "用户同意提交，销售单已提交审核。";
            case SalesOrderActionReceipt.STATUS_CANCELLED -> "用户同意取消，销售单已取消。";
            case SalesOrderActionReceipt.STATUS_REJECTED -> "用户暂不执行，销售单保持原状。";
            case SalesOrderActionReceipt.STATUS_FAILED -> "用户同意后业务接口拒绝了操作。";
            default -> "用户已处理。";
        };
    }

    /** 生成给模型看的结论文字。 */
    private String conclusion(SalesOrderDraftReceipt receipt) {
        return switch (receipt.status()) {
            case SalesOrderDraftReceipt.STATUS_CREATED -> "用户确认创建，销售单已生成草稿。";
            case SalesOrderDraftReceipt.STATUS_UPDATED -> "用户确认修改，销售单已保存。";
            case SalesOrderDraftReceipt.STATUS_EDITED -> "用户修改参数后确认，销售单已保存。";
            case SalesOrderDraftReceipt.STATUS_FAILED -> "用户确认后业务接口拒绝了操作。";
            case SalesOrderDraftReceipt.STATUS_REJECTED -> "用户驳回了这次草稿，未创建或保存。";
            default -> "用户已处理。";
        };
    }
}
