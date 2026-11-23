package com.qiheng.erp.ai.action;

import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.sql.scope.PermissionCodes;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.service.LoginUserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 提交与取消的等待通道：把不需要业务参数的动作交给用户确认，再把结果交回模型。
 *
 * @author Li
 * @since 2026-11-23
 */
public final class PurchaseOrderActionService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderActionService.class);

    /** 与草稿确认共用同一套可信事件通道约定。 */
    public static final String EVENT_KEY = "purchaseActionEvent";

    /** 等待超时标记。 */
    static final String TIMEOUT_MARKER = "PURCHASE_ACTION_WAIT_TIMEOUT";

    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();

    private final AiConversationMapper conversations;

    private final LoginUserService users;

    private final ConversationMessageStore messages;

    private final ObjectMapper mapper;

    private final Duration timeout;

    private record Pending(PurchaseOrderActionType type, long userId, long conversationId, String runId,
                           String purchaseOrderId, String orderNo, CompletableFuture<String> receipt) {}

    /**
     * 使用默认等待时长构造服务。
     *
     * @param conversations 会话数据访问
     * @param users 登录用户查询
     * @param messages 会话消息存储
     * @param mapper JSON 序列化工具
     */
    public PurchaseOrderActionService(AiConversationMapper conversations, LoginUserService users,
                                      ConversationMessageStore messages, ObjectMapper mapper) {
        this(conversations, users, messages, mapper, Duration.ofMinutes(30));
    }

    /**
     * 使用指定等待时长构造服务。
     *
     * @param conversations 会话数据访问
     * @param users 登录用户查询
     * @param messages 会话消息存储
     * @param mapper JSON 序列化工具
     * @param timeout 等待用户处置的最长时间
     */
    public PurchaseOrderActionService(AiConversationMapper conversations, LoginUserService users,
                                      ConversationMessageStore messages, ObjectMapper mapper, Duration timeout) {
        this.conversations = conversations;
        this.users = users;
        this.messages = messages;
        this.mapper = mapper;
        this.timeout = timeout;
    }

    /**
     * 发出动作确认事件并等待用户处置。
     *
     * @param type 提交或取消
     * @param userId 发起任务的用户
     * @param conversationId 当前会话
     * @param runId 本轮运行标识
     * @param request 动作参数
     * @param events 本轮事件出口
     * @return 交回模型的结论文本
     * @throws Exception 等待被中断或超时
     */
    public String request(PurchaseOrderActionType type, long userId, long conversationId, String runId,
                          PurchaseOrderActionRequest request, Consumer<NodeOutput> events) throws Exception {
        if (request == null || isBlank(request.purchaseOrderId())) {
            return "采购单操作缺少 purchaseOrderId，请先确认要操作的采购单。";
        }
        String orderId = request.purchaseOrderId().trim();
        String orderNo = trimToNull(request.orderNo());
        String id = UUID.randomUUID().toString();
        var waiting = new Pending(type, userId, conversationId, runId, orderId, orderNo, new CompletableFuture<>());
        pending.put(id, waiting);
        try {
            var state = new OverAllState(Map.of(
                    "actionRequestId", id,
                    "action", type.name(),
                    "purchaseOrderId", orderId,
                    "orderNo", orderNo == null ? "" : orderNo,
                    "reason", trimToNull(request.reason()) == null ? "" : request.reason().trim()));
            events.accept(NodeOutput.of("purchase_action", "main_agent", state, null));
            return waiting.receipt().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            if (!waiting.receipt().completeExceptionally(error)) {
                return waiting.receipt().join();
            }
            throw new IllegalStateException(TIMEOUT_MARKER, error);
        } finally {
            pending.remove(id, waiting);
        }
    }

    /**
     * 提交当前用户对采购单动作的处置结果。
     *
     * @param conversationId 会话编号
     * @param requestId 动作请求编号
     * @param receipt 处置结果
     */
    public void resolveForCurrentUser(long conversationId, String requestId, PurchaseOrderActionReceipt receipt) {
        resolveForUser(UserContext.requireCurrentUser().getUserId(), conversationId, requestId, receipt);
    }

    /**
     * 仅供同包可信调用方及测试使用，不接受客户端传入身份。
     *
     * @param userId 用户编号
     * @param conversationId 会话编号
     * @param requestId 动作请求编号
     * @param receipt 处置结果
     */
    void resolveForUser(long userId, long conversationId, String requestId, PurchaseOrderActionReceipt receipt) {
        if (conversations.selectOwned(conversationId, userId) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        var user = users.findByUserId(userId);
        var permissions = PermissionCodes.of(user);
        if (user == null || (!permissions.contains("*") && !permissions.contains("ai:assistant:use"))) {
            throw new IllegalStateException("当前用户无 AI 使用权限");
        }
        var request = pending.get(requestId);
        if (request == null || request.userId() != userId || request.conversationId() != conversationId) {
            throw new IllegalStateException("采购单操作不存在或已结束");
        }
        if (receipt == null || receipt.status() == null) {
            throw new IllegalArgumentException("请提供处置结果");
        }
        var result = switch (receipt.status()) {
            case PurchaseOrderActionReceipt.STATUS_SUBMITTED -> {
                requireType(request.type(), PurchaseOrderActionType.SUBMIT, "提交");
                yield submitted(request, receipt);
            }
            case PurchaseOrderActionReceipt.STATUS_CANCELLED -> {
                requireType(request.type(), PurchaseOrderActionType.CANCEL, "取消");
                yield cancelled(request, receipt);
            }
            case PurchaseOrderActionReceipt.STATUS_REJECTED -> rejected(request, receipt);
            case PurchaseOrderActionReceipt.STATUS_FAILED -> failed(request, receipt);
            default -> throw new IllegalArgumentException("不支持的处置状态：" + receipt.status());
        };
        record(request, requestId, receipt);
        if (!request.receipt().complete(result)) {
            throw new IllegalStateException("采购单操作已处置");
        }
    }

    /** 校验动作类型与处置状态是否匹配。 */
    private void requireType(PurchaseOrderActionType actual, PurchaseOrderActionType expected, String label) {
        if (actual != expected) {
            throw new IllegalArgumentException("当前采购单动作不支持「" + label + "」结果");
        }
    }

    /** 用户同意提交。 */
    private String submitted(Pending request, PurchaseOrderActionReceipt receipt) {
        var text = new StringBuilder("用户同意提交，采购单 ");
        text.append(label(request, receipt)).append(" 已提交审核");
        String status = statusText(receipt.orderStatus());
        if (status != null) {
            text.append("，当前状态为").append(status);
        } else {
            text.append("，当前状态为待审核");
        }
        text.append("。");
        String arrival = trimToNull(receipt.arrivalDate());
        if (arrival != null) {
            text.append("\n预计到货：").append(arrival);
        }
        return text.toString();
    }

    /** 用户同意取消。 */
    private String cancelled(Pending request, PurchaseOrderActionReceipt receipt) {
        var text = new StringBuilder("用户同意取消，采购单 ");
        text.append(label(request, receipt)).append(" 已取消。");
        String reason = trimToNull(receipt.reason());
        if (reason != null) {
            text.append("\n原因：").append(reason);
        }
        return text.toString();
    }

    /** 用户暂不执行。 */
    private String rejected(Pending request, PurchaseOrderActionReceipt receipt) {
        String action = request.type() == PurchaseOrderActionType.SUBMIT ? "提交" : "取消";
        var text = new StringBuilder("用户暂不").append(action).append("，采购单 ");
        text.append(label(request, receipt));
        text.append(request.type() == PurchaseOrderActionType.SUBMIT ? " 保持草稿状态。" : " 保持当前状态。");
        String reason = trimToNull(receipt.reason());
        if (reason != null) {
            text.append("\n原因：").append(reason);
        }
        return text.toString();
    }

    /** 系统接口拒绝。 */
    private String failed(Pending request, PurchaseOrderActionReceipt receipt) {
        String error = trimToNull(receipt.error());
        if (error == null) {
            throw new IllegalArgumentException("请提供系统拒绝的原因");
        }
        String action = request.type() == PurchaseOrderActionType.SUBMIT ? "提交未通过" : "取消失败";
        var text = new StringBuilder("采购单 ").append(label(request, receipt))
                .append(" ").append(action).append("：").append(error).append("。");
        var params = receipt.orderParams();
        if (params != null && !params.isEmpty()) {
            text.append("\n当前单据：").append(toJson(params));
        }
        return text.toString();
    }

    /** 回执里引用的单号；缺失时用「ID：xxx」兜底。 */
    private String label(Pending request, PurchaseOrderActionReceipt receipt) {
        String orderNo = trimToNull(receipt.orderNo());
        if (orderNo != null) {
            return orderNo;
        }
        if (request.orderNo() != null) {
            return request.orderNo();
        }
        return "ID：" + request.purchaseOrderId();
    }

    /** 保存一条轻量的动作记录，供会话历史回看。 */
    private void record(Pending request, String actionRequestId, PurchaseOrderActionReceipt receipt) {
        if (messages == null || isBlank(request.runId())) {
            return;
        }
        try {
            var content = new LinkedHashMap<String, Object>();
            content.put("name", request.type() == PurchaseOrderActionType.SUBMIT
                    ? "submit_purchase_order" : "cancel_purchase_order");
            content.put("action", request.type().name());
            content.put("status", receipt.status());
            putIfPresent(content, "purchaseOrderId", request.purchaseOrderId());
            putIfPresent(content, "orderNo", trimToNull(receipt.orderNo()) != null
                    ? receipt.orderNo().trim() : request.orderNo());
            putIfPresent(content, "orderStatus", trimToNull(receipt.orderStatus()));
            putIfPresent(content, "error", trimToNull(receipt.error()));
            putIfPresent(content, "reason", trimToNull(receipt.reason()));
            messages.append(new AiMessage()
                    .setUserId(request.userId())
                    .setConversationId(request.conversationId())
                    .setRequestId(request.runId())
                    .setMessageKey(request.runId() + ":action:" + actionRequestId)
                    .setRole("assistant")
                    .setKind("TOOL_CALL")
                    .setContent(mapper.writeValueAsString(content)));
        } catch (RuntimeException | JsonProcessingException error) {
            log.warn("采购单动作记录保存失败 conversationId={} requestId={}",
                    request.conversationId(), actionRequestId, error);
        }
    }

    /** 把参数序列化成单行 JSON。 */
    private String toJson(Map<String, Object> params) {
        try {
            return mapper.writeValueAsString(params);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("采购单参数序列化失败", error);
        }
    }

    /** 把单据状态编码转成用户可读文字。 */
    private String statusText(String status) {
        String value = trimToNull(status);
        if (value == null) {
            return null;
        }
        return switch (value) {
            case "DRAFT" -> "草稿";
            case "SUBMITTED" -> "待审核";
            case "APPROVED" -> "待入库";
            case "PARTIAL_INBOUND" -> "部分入库";
            case "INBOUND_DONE" -> "已入库";
            case "CANCELLED" -> "已取消";
            default -> value;
        };
    }

    /** 只在有值时写入字段。 */
    private void putIfPresent(Map<String, Object> target, String field, String value) {
        if (value != null) {
            target.put(field, value);
        }
    }

    /** 去空格后的空值或正文。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 判断字符串是否为空。 */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}