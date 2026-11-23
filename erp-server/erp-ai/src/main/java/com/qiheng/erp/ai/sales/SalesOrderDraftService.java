package com.qiheng.erp.ai.sales;

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

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * 管理等待用户处置的销售草稿：发出确认事件、接收处置结果、把事实交回模型。
 *
 * <p>交回模型的是给人看的结论加结构化参数：主句说明用户做了什么，
 * 参数块保留机器可读的字段，模型既不用从自然语言里抠数字，也不会把 JSON 误读成结论。</p>
 *
 * @author Li
 * @since 2026-11-22
 */
public final class SalesOrderDraftService {

    private static final Logger log = LoggerFactory.getLogger(SalesOrderDraftService.class);

    /** 与追问共用同一套可信事件通道键名约定，由主智能体注入。 */
    public static final String EVENT_KEY = "salesOrderDraftEvent";

    /** 等待超时标记；拦截器据此直接结束本轮。 */
    static final String TIMEOUT_MARKER = "SALES_ORDER_DRAFT_WAIT_TIMEOUT";

    private static final int MAX_ITEMS = 100;

    private static final List<String> HEADER_FIELDS = List.of("customerId", "warehouseId", "expectedDeliveryDate", "remark");

    private static final List<String> ITEM_FIELDS = List.of("productId", "quantity", "unitPrice", "remark");

    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();

    private final AiConversationMapper conversations;

    private final LoginUserService users;

    private final ConversationMessageStore messages;

    private final ObjectMapper mapper;

    private final Duration timeout;

    private record Pending(SalesOrderDraftAction action, long userId, long conversationId, String runId,
                           Map<String, Object> draft, CompletableFuture<String> receipt) {}

    /**
     * 使用默认等待时长构造服务。
     *
     * @param conversations 会话数据访问
     * @param users 登录用户查询
     * @param messages 会话消息存储
     * @param mapper JSON 序列化工具
     */
    public SalesOrderDraftService(AiConversationMapper conversations, LoginUserService users,
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
    public SalesOrderDraftService(AiConversationMapper conversations, LoginUserService users,
                                ConversationMessageStore messages, ObjectMapper mapper, Duration timeout) {
        this.conversations = conversations;
        this.users = users;
        this.messages = messages;
        this.mapper = mapper;
        this.timeout = timeout;
    }

    /**
     * 校验草稿参数，发出确认事件并等待用户处置。
     *
     * @param action 创建新草稿或修改已有单据
     * @param userId 发起任务的用户
     * @param conversationId 当前会话
     * @param runId 本轮运行标识，用于把草稿记录归到同一轮对话
     * @param request 草稿参数
     * @param events 本轮事件出口
     * @return 交回模型的结论文本，包含结构化参数
     * @throws Exception 等待被中断或超时
     */
    public String request(SalesOrderDraftAction action, long userId, long conversationId, String runId,
                          SalesOrderDraftRequest request, Consumer<NodeOutput> events) throws Exception {
        String invalid = validate(action, request);
        if (invalid != null) {
            return invalid;
        }
        var draft = normalize(request);
        String id = UUID.randomUUID().toString();
        var waiting = new Pending(action, userId, conversationId, runId, draft, new CompletableFuture<>());
        pending.put(id, waiting);
        try {
            var state = new OverAllState(Map.of("draftRequestId", id, "action", action.name(), "draft", draft));
            events.accept(NodeOutput.of("sales_order_draft", "main_agent", state, null));
            return waiting.receipt().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            // 处置结果与超时竞争时只允许一个结果生效，避免前端误报提交成功。
            if (!waiting.receipt().completeExceptionally(error)) {
                return waiting.receipt().join();
            }
            throw new IllegalStateException(TIMEOUT_MARKER, error);
        } finally {
            pending.remove(id, waiting);
        }
    }

    /**
     * 提交当前用户对草稿的处置结果。
     *
     * @param conversationId 会话编号
     * @param draftRequestId 草稿请求编号
     * @param receipt 处置结果
     */
    public void resolveForCurrentUser(long conversationId, String draftRequestId, SalesOrderDraftReceipt receipt) {
        resolveForUser(UserContext.requireCurrentUser().getUserId(), conversationId, draftRequestId, receipt);
    }

    /**
     * 仅供同包可信调用方及测试使用，不接受客户端传入身份。
     *
     * @param userId 用户编号
     * @param conversationId 会话编号
     * @param draftRequestId 草稿请求编号
     * @param receipt 处置结果
     */
    void resolveForUser(long userId, long conversationId, String draftRequestId, SalesOrderDraftReceipt receipt) {
        if (conversations.selectOwned(conversationId, userId) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        var user = users.findByUserId(userId);
        var permissions = PermissionCodes.of(user);
        if (user == null || (!permissions.contains("*") && !permissions.contains("ai:assistant:use"))) {
            throw new IllegalStateException("当前用户无 AI 使用权限");
        }
        var request = pending.get(draftRequestId);
        if (request == null || request.userId() != userId || request.conversationId() != conversationId) {
            throw new IllegalStateException("草稿不存在或已结束");
        }
        if (receipt == null || receipt.status() == null) {
            throw new IllegalArgumentException("请提供处置结果");
        }
        var result = switch (receipt.status()) {
            case SalesOrderDraftReceipt.STATUS_CREATED -> {
                if (request.action() != SalesOrderDraftAction.CREATE) {
                    throw new IllegalArgumentException("修改销售单不能使用 created 状态");
                }
                yield confirmed(request.action(), receipt);
            }
            case SalesOrderDraftReceipt.STATUS_UPDATED -> {
                if (request.action() != SalesOrderDraftAction.UPDATE) {
                    throw new IllegalArgumentException("创建销售单不能使用 updated 状态");
                }
                yield confirmed(request.action(), receipt);
            }
            case SalesOrderDraftReceipt.STATUS_EDITED -> edited(request.action(), request.draft(), receipt);
            case SalesOrderDraftReceipt.STATUS_FAILED -> failed(request.action(), receipt);
            case SalesOrderDraftReceipt.STATUS_REJECTED -> rejected(request.action(), receipt);
            default -> throw new IllegalArgumentException("不支持的处置状态：" + receipt.status());
        };
        record(request, draftRequestId, receipt);
        if (!request.receipt().complete(result)) {
            throw new IllegalStateException("草稿已处置");
        }
    }

    /**
     * 保存一份已处置的草稿，供会话历史以只读卡片回看。
     *
     * <p>保存失败不影响用户已经完成的处置，只记录日志。</p>
     */
    private void record(Pending pending, String draftRequestId, SalesOrderDraftReceipt receipt) {
        if (messages == null || isBlank(pending.runId())) {
            return;
        }
        try {
            var content = new LinkedHashMap<String, Object>();
            content.put("name", pending.action() == SalesOrderDraftAction.CREATE
                    ? "prepare_sales_order_draft" : "update_sales_order_draft");
            content.put("action", pending.action().name());
            content.put("status", receipt.status());
            putIfPresent(content, "salesOrderId", trimToNull(receipt.salesOrderId()));
            putIfPresent(content, "orderNo", trimToNull(receipt.orderNo()));
            putIfPresent(content, "orderStatus", trimToNull(receipt.orderStatus()));
            putIfPresent(content, "error", trimToNull(receipt.error()));
            putIfPresent(content, "userNote", trimToNull(receipt.userNote()));
            putIfPresent(content, "suggestion", trimToNull(receipt.suggestion()));
            var reasons = reasonsOf(receipt);
            if (!reasons.isEmpty()) {
                content.put("reasons", reasons);
            }
            // 只有用户真正改过并创建的单据才需要记录变化字段。
            if (SalesOrderDraftReceipt.STATUS_EDITED.equals(receipt.status())) {
                var finalParams = sanitize(receipt.finalParams());
                if (finalParams != null) {
                    content.put("changedFields", diff(pending.draft(), finalParams));
                }
            }
            messages.append(new AiMessage()
                    .setUserId(pending.userId())
                    .setConversationId(pending.conversationId())
                    .setRequestId(pending.runId())
                    .setMessageKey(pending.runId() + ":draft:" + draftRequestId)
                    .setRole("assistant")
                    .setKind("TOOL_CALL")
                    .setContent(mapper.writeValueAsString(content)));
        } catch (RuntimeException | JsonProcessingException error) {
            log.warn("销售草稿记录保存失败 conversationId={} draftRequestId={}",
                    pending.conversationId(), draftRequestId, error);
        }
    }

    /** 校验草稿参数；合法时返回 null。 */
    private String validate(SalesOrderDraftAction action, SalesOrderDraftRequest request) {
        if (request == null) {
            return "销售草稿格式错误：缺少草稿参数，请补充后重试。";
        }
        boolean update = action == SalesOrderDraftAction.UPDATE;
        if (update) {
            if (isBlank(request.salesOrderId())) {
                return "销售单修改缺少 salesOrderId，请先确认要修改的单据。";
            }
            // 修改允许只传变化字段；不需要改明细时省略 items。
            if (request.items() == null) {
                return null;
            }
            if (request.items().isEmpty()) {
                return "销售单修改格式错误：items 传了就不能为空，不需要改明细时省略该字段。";
            }
        } else {
            if (isBlank(request.customerId())) {
                return "销售草稿格式错误：customerId 不能为空，请先确认客户。";
            }
            if (isBlank(request.warehouseId())) {
                return "销售草稿格式错误：warehouseId 不能为空，请先确认出库仓库。";
            }
            if (request.items() == null || request.items().isEmpty()) {
                return "销售草稿格式错误：items 至少需要一行销售明细。";
            }
        }
        if (request.items().size() > MAX_ITEMS) {
            return "销售草稿格式错误：单次草稿最多 " + MAX_ITEMS + " 行明细，请拆分后重试。";
        }
        var productIds = new HashSet<String>();
        for (var item : request.items()) {
            if (item == null || isBlank(item.productId())) {
                return "销售草稿格式错误：明细行的 productId 不能为空。";
            }
            if (!productIds.add(item.productId())) {
                return "销售草稿格式错误：同一产品只能出现一行明细：" + item.productId();
            }
            if (item.quantity() == null || item.quantity().signum() <= 0) {
                return "销售草稿格式错误：明细数量必须大于 0：" + item.productId();
            }
            // 销售单必须有明确成交价，单价不允许留空。
            if (item.unitPrice() == null) {
                return "销售草稿格式错误：明细单价不能为空：" + item.productId();
            }
            if (item.unitPrice().signum() < 0) {
                return "销售草稿格式错误：明细单价不能小于 0：" + item.productId();
            }
        }
        return null;
    }

    /** 把工具参数规范化成可序列化的展示结构。 */
    private Map<String, Object> normalize(SalesOrderDraftRequest request) {
        var draft = new LinkedHashMap<String, Object>();
        draft.put("salesOrderId", trimToNull(request.salesOrderId()));
        draft.put("customerId", request.customerId());
        draft.put("warehouseId", request.warehouseId());
        draft.put("expectedDeliveryDate", trimToNull(request.expectedDeliveryDate()));
        draft.put("remark", trimToNull(request.remark()));
        var items = new ArrayList<Map<String, Object>>();
        // 修改允许不传明细：空数组表示沿用单据原明细。
        if (request.items() != null) {
            for (var item : request.items()) {
                var line = new LinkedHashMap<String, Object>();
                line.put("productId", item.productId());
                line.put("quantity", item.quantity());
                line.put("unitPrice", item.unitPrice());
                line.put("remark", trimToNull(item.remark()));
                items.add(line);
            }
        }
        draft.put("items", items);
        return draft;
    }

    /** 用户直接确认：创建给出单号和草稿单信息，修改给出单号和最终参数。 */
    private String confirmed(SalesOrderDraftAction action, SalesOrderDraftReceipt receipt) {
        String orderNo = trimToNull(receipt.orderNo());
        String orderId = trimToNull(receipt.salesOrderId());
        var text = new StringBuilder();
        if (action == SalesOrderDraftAction.CREATE) {
            text.append("用户确认创建，销售单 ");
            text.append(orderNo == null ? "已生成草稿。" : orderNo + " 已生成草稿。");
            var block = new LinkedHashMap<String, Object>();
            putIfPresent(block, "salesOrderId", orderId);
            putIfPresent(block, "orderNo", orderNo);
            if (!block.isEmpty()) {
                text.append("\n草稿单：").append(toJson(block));
            }
            return text.toString();
        }
        text.append("用户确认修改，销售单 ");
        text.append(orderNo == null ? "已保存。" : orderNo + " 已保存。");
        String status = statusText(receipt.orderStatus());
        if (status != null) {
            text.append("当前状态为").append(status).append("。");
        }
        var finalParams = sanitize(receipt.finalParams());
        if (finalParams != null) {
            text.append("\n最终参数：").append(toJson(finalParams));
        }
        return text.toString();
    }

    /** 用户修改后创建或保存：说明结果、最终参数、变化字段和用户的修改说明。 */
    private String edited(SalesOrderDraftAction action, Map<String, Object> draft, SalesOrderDraftReceipt receipt) {
        var finalParams = sanitize(receipt.finalParams());
        if (finalParams == null) {
            throw new IllegalArgumentException("请提供用户确认后的最终参数");
        }
        String orderNo = trimToNull(receipt.orderNo());
        var text = new StringBuilder();
        if (action == SalesOrderDraftAction.CREATE) {
            text.append("用户修改参数后确认创建，销售单 ");
            text.append(orderNo == null ? "已生成草稿。" : orderNo + " 已生成草稿。");
        } else {
            text.append("用户修改参数后保存，销售单 ");
            text.append(orderNo == null ? "已更新。" : orderNo + " 已更新。");
            String status = statusText(receipt.orderStatus());
            if (status != null) {
                text.append("当前状态为").append(status).append("。");
            }
        }
        text.append("\n最终参数：").append(toJson(finalParams));
        var changes = diff(draft, finalParams);
        text.append("\n修改的字段：").append(changes.isEmpty() ? "无（用户未改变内容）" : String.join("、", changes));
        String note = trimToNull(receipt.userNote());
        if (note != null) {
            text.append("\n用户说明：").append(note);
        }
        return text.toString();
    }

    /** 系统拒绝：只说明规则原因和被拒参数，不评价用户改了什么。 */
    private String failed(SalesOrderDraftAction action, SalesOrderDraftReceipt receipt) {
        String error = trimToNull(receipt.error());
        if (error == null) {
            throw new IllegalArgumentException("请提供系统拒绝的原因");
        }
        String orderNo = trimToNull(receipt.orderNo());
        var text = new StringBuilder();
        if (action == SalesOrderDraftAction.CREATE) {
            text.append("创建未通过，未生成销售单：").append(error).append("。");
        } else {
            text.append("销售单 ").append(orderNo == null ? "" : orderNo + " ")
                    .append("修改未保存：").append(error).append("。");
        }
        var finalParams = sanitize(receipt.finalParams());
        if (finalParams != null) {
            text.append("\n被拒参数：").append(toJson(finalParams));
        }
        return text.toString();
    }

    /** 用户驳回：说明驳回原因和补充要求。 */
    private String rejected(SalesOrderDraftAction action, SalesOrderDraftReceipt receipt) {
        var reasons = reasonsOf(receipt);
        String suggestion = trimToNull(receipt.suggestion());
        if (reasons.isEmpty() && suggestion == null) {
            throw new IllegalArgumentException("请说明驳回原因或补充要求");
        }
        String orderNo = trimToNull(receipt.orderNo());
        var text = new StringBuilder();
        if (action == SalesOrderDraftAction.CREATE) {
            text.append("用户驳回了销售草稿，未生成销售单。");
        } else {
            text.append("用户驳回了这次修改，销售单 ").append(orderNo == null ? "" : orderNo + " ")
                    .append("保持原样。");
        }
        if (!reasons.isEmpty()) {
            text.append("\n").append(action == SalesOrderDraftAction.CREATE ? "驳回原因：" : "原因：")
                    .append(String.join("、", reasons));
        }
        if (suggestion != null) {
            text.append("\n补充要求：").append(suggestion);
        }
        return text.toString();
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
            case "APPROVED" -> "待出库";
            case "PARTIAL_OUTBOUND" -> "部分出库";
            case "OUTBOUND_DONE" -> "已出库";
            case "CANCELLED" -> "已取消";
            default -> value;
        };
    }

    /** 提取并清洗驳回原因。 */
    private List<String> reasonsOf(SalesOrderDraftReceipt receipt) {
        return receipt.reasons() == null ? List.of()
                : receipt.reasons().stream().filter(Objects::nonNull).map(String::trim)
                .filter(value -> !value.isEmpty()).distinct().toList();
    }

    /** 只在有值时写入字段，避免参数块出现无意义的 null。 */
    private void putIfPresent(Map<String, Object> target, String field, String value) {
        if (value != null) {
            target.put(field, value);
        }
    }

    /** 过滤前端派生字段，只保留工具参数结构内的业务字段。 */
    private Map<String, Object> sanitize(Map<String, Object> params) {
        if (params == null) {
            return null;
        }
        var target = new LinkedHashMap<String, Object>();
        for (String field : HEADER_FIELDS) {
            target.put(field, normalizeValue(field, params.get(field)));
        }
        if (!(params.get("items") instanceof List<?> lines) || lines.isEmpty()) {
            return null;
        }
        var items = new ArrayList<Map<String, Object>>();
        for (Object line : lines) {
            if (!(line instanceof Map<?, ?> source)) {
                return null;
            }
            var item = new LinkedHashMap<String, Object>();
            for (String field : ITEM_FIELDS) {
                item.put(field, normalizeValue(field, source.get(field)));
            }
            if (item.get("productId") == null || item.get("quantity") == null) {
                return null;
            }
            items.add(item);
        }
        target.put("items", items);
        return target;
    }

    /** 统一字段的展示形式：文本去空格，数字转 BigDecimal。 */
    private Object normalizeValue(String field, Object value) {
        if (value == null) {
            return null;
        }
        if ("quantity".equals(field) || "unitPrice".equals(field)) {
            if (value instanceof BigDecimal decimal) {
                return decimal;
            }
            if (value instanceof Number number) {
                return new BigDecimal(number.toString());
            }
            String text = String.valueOf(value).trim();
            try {
                return text.isEmpty() ? null : new BigDecimal(text);
            } catch (NumberFormatException error) {
                return text;
            }
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /** 比较草稿与最终参数，返回发生变化的字段路径。 */
    private List<String> diff(Map<String, Object> draft, Map<String, Object> finalParams) {
        var changes = new ArrayList<String>();
        for (String field : HEADER_FIELDS) {
            if (!sameValue(draft.get(field), finalParams.get(field))) {
                changes.add(field);
            }
        }
        List<?> before = draft.get("items") instanceof List<?> lines ? lines : List.of();
        List<?> after = finalParams.get("items") instanceof List<?> lines ? lines : List.of();
        // 修改时没有传明细：沿用单据原明细，不参与变化比较。
        if (before.isEmpty()) {
            return changes;
        }
        if (before.size() != after.size()) {
            changes.add("items");
            return changes;
        }
        for (int index = 0; index < before.size(); index++) {
            if (!(before.get(index) instanceof Map<?, ?> source) || !(after.get(index) instanceof Map<?, ?> target)) {
                changes.add("items[" + index + "]");
                continue;
            }
            for (String field : ITEM_FIELDS) {
                if (!sameValue(source.get(field), target.get(field))) {
                    changes.add("items[" + index + "]." + field);
                }
            }
        }
        return changes;
    }

    /** 比较两个规范化后的值；数字按数值比较，避免 100 与 100.00 被当成变化。 */
    private boolean sameValue(Object left, Object right) {
        Object one = normalizeValue("quantity", left);
        Object two = normalizeValue("quantity", right);
        if (one instanceof BigDecimal first && two instanceof BigDecimal second) {
            return first.compareTo(second) == 0;
        }
        return Objects.equals(one, two);
    }

    /** 把参数块序列化成单行 JSON，保证模型能直接解析。 */
    private String toJson(Map<String, Object> params) {
        try {
            return mapper.writeValueAsString(params);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("销售草稿参数序列化失败", error);
        }
    }

    /** 把文本参数规范化为去空格后的空值或正文。 */
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