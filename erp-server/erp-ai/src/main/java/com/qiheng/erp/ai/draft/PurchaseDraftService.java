package com.qiheng.erp.ai.draft;

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
 * 管理等待用户处置的采购草稿：发出确认事件、接收处置结果、把事实交回模型。
 *
 * <p>交回模型的是给人看的结论加结构化参数：主句说明用户做了什么，
 * 参数块保留机器可读的字段，模型既不用从自然语言里抠数字，也不会把 JSON 误读成结论。</p>
 *
 * @author Li
 * @since 2026-11-22
 */
public final class PurchaseDraftService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseDraftService.class);

    /** 与追问共用同一套可信事件通道键名约定，由主智能体注入。 */
    public static final String EVENT_KEY = "purchaseDraftEvent";

    /** 等待超时标记；拦截器据此直接结束本轮。 */
    static final String TIMEOUT_MARKER = "PURCHASE_DRAFT_WAIT_TIMEOUT";

    private static final int MAX_ITEMS = 100;

    private static final List<String> HEADER_FIELDS = List.of("supplierId", "warehouseId", "expectedArrivalDate", "remark");

    private static final List<String> ITEM_FIELDS = List.of("productId", "quantity", "unitPrice", "remark");

    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();

    private final AiConversationMapper conversations;

    private final LoginUserService users;

    private final ConversationMessageStore messages;

    private final ObjectMapper mapper;

    private final Duration timeout;

    private record Pending(long userId, long conversationId, String runId, Map<String, Object> draft,
                           CompletableFuture<String> receipt) {}

    /**
     * 使用默认等待时长构造服务。
     *
     * @param conversations 会话数据访问
     * @param users 登录用户查询
     * @param messages 会话消息存储
     * @param mapper JSON 序列化工具
     */
    public PurchaseDraftService(AiConversationMapper conversations, LoginUserService users,
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
    public PurchaseDraftService(AiConversationMapper conversations, LoginUserService users,
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
     * @param userId 发起任务的用户
     * @param conversationId 当前会话
     * @param runId 本轮运行标识，用于把草稿记录归到同一轮对话
     * @param request 草稿参数
     * @param events 本轮事件出口
     * @return 交回模型的结论文本，包含结构化参数
     * @throws Exception 等待被中断或超时
     */
    public String request(long userId, long conversationId, String runId, PurchaseOrderDraftRequest request,
                          Consumer<NodeOutput> events) throws Exception {
        String invalid = validate(request);
        if (invalid != null) {
            return invalid;
        }
        var draft = normalize(request);
        String id = UUID.randomUUID().toString();
        var waiting = new Pending(userId, conversationId, runId, draft, new CompletableFuture<>());
        pending.put(id, waiting);
        try {
            var state = new OverAllState(Map.of("draftRequestId", id, "draft", draft));
            events.accept(NodeOutput.of("purchase_draft", "main_agent", state, null));
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
    public void resolveForCurrentUser(long conversationId, String draftRequestId, PurchaseDraftReceipt receipt) {
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
    void resolveForUser(long userId, long conversationId, String draftRequestId, PurchaseDraftReceipt receipt) {
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
            case PurchaseDraftReceipt.STATUS_CREATED -> created(receipt);
            case PurchaseDraftReceipt.STATUS_EDITED -> edited(request.draft(), receipt);
            case PurchaseDraftReceipt.STATUS_FAILED -> failed(receipt);
            case PurchaseDraftReceipt.STATUS_REJECTED -> rejected(receipt);
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
    private void record(Pending pending, String draftRequestId, PurchaseDraftReceipt receipt) {
        if (messages == null || isBlank(pending.runId())) {
            return;
        }
        try {
            var content = new LinkedHashMap<String, Object>();
            content.put("name", "prepare_purchase_order_draft");
            content.put("status", receipt.status());
            putIfPresent(content, "purchaseOrderId", trimToNull(receipt.purchaseOrderId()));
            putIfPresent(content, "orderNo", trimToNull(receipt.orderNo()));
            putIfPresent(content, "error", trimToNull(receipt.error()));
            putIfPresent(content, "userNote", trimToNull(receipt.userNote()));
            putIfPresent(content, "suggestion", trimToNull(receipt.suggestion()));
            var reasons = reasonsOf(receipt);
            if (!reasons.isEmpty()) {
                content.put("reasons", reasons);
            }
            // 只有用户真正改过并创建的单据才需要记录变化字段。
            if (PurchaseDraftReceipt.STATUS_EDITED.equals(receipt.status())) {
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
            log.warn("采购草稿记录保存失败 conversationId={} draftRequestId={}",
                    pending.conversationId(), draftRequestId, error);
        }
    }

    /** 校验草稿参数；合法时返回 null。 */
    private String validate(PurchaseOrderDraftRequest request) {
        if (request == null) {
            return "采购草稿格式错误：缺少草稿参数，请补充后重试。";
        }
        if (isBlank(request.supplierId())) {
            return "采购草稿格式错误：supplierId 不能为空，请先确认供应商。";
        }
        if (isBlank(request.warehouseId())) {
            return "采购草稿格式错误：warehouseId 不能为空，请先确认入库仓库。";
        }
        if (request.items() == null || request.items().isEmpty()) {
            return "采购草稿格式错误：items 至少需要一行采购明细。";
        }
        if (request.items().size() > MAX_ITEMS) {
            return "采购草稿格式错误：单次草稿最多 " + MAX_ITEMS + " 行明细，请拆分后重试。";
        }
        var productIds = new HashSet<String>();
        for (var item : request.items()) {
            if (item == null || isBlank(item.productId())) {
                return "采购草稿格式错误：明细行的 productId 不能为空。";
            }
            if (!productIds.add(item.productId())) {
                return "采购草稿格式错误：同一产品只能出现一行明细：" + item.productId();
            }
            if (item.quantity() == null || item.quantity().signum() <= 0) {
                return "采购草稿格式错误：明细数量必须大于 0：" + item.productId();
            }
            if (item.unitPrice() != null && item.unitPrice().signum() < 0) {
                return "采购草稿格式错误：明细单价不能小于 0：" + item.productId();
            }
        }
        return null;
    }

    /** 把工具参数规范化成可序列化的展示结构。 */
    private Map<String, Object> normalize(PurchaseOrderDraftRequest request) {
        var draft = new LinkedHashMap<String, Object>();
        draft.put("supplierId", request.supplierId());
        draft.put("warehouseId", request.warehouseId());
        draft.put("expectedArrivalDate", trimToNull(request.expectedArrivalDate()));
        draft.put("remark", trimToNull(request.remark()));
        var items = new ArrayList<Map<String, Object>>();
        for (var item : request.items()) {
            var line = new LinkedHashMap<String, Object>();
            line.put("productId", item.productId());
            line.put("quantity", item.quantity());
            line.put("unitPrice", item.unitPrice());
            line.put("remark", trimToNull(item.remark()));
            items.add(line);
        }
        draft.put("items", items);
        return draft;
    }

    /** 用户直接确认：说明结果和单号，参数就是模型提交的原稿，不再重复。 */
    private String created(PurchaseDraftReceipt receipt) {
        String orderNo = trimToNull(receipt.orderNo());
        return orderNo == null
                ? "用户接受了你的采购草稿参数，草稿单已创建。"
                : "用户接受了你的采购草稿参数，已生成单号为 " + orderNo + " 的草稿单。";
    }

    /** 用户修改后创建：说明结果、最终参数、变化字段和用户的修改说明。 */
    private String edited(Map<String, Object> draft, PurchaseDraftReceipt receipt) {
        var finalParams = sanitize(receipt.finalParams());
        if (finalParams == null) {
            throw new IllegalArgumentException("请提供用户确认后的最终参数");
        }
        String orderNo = trimToNull(receipt.orderNo());
        var text = new StringBuilder("用户修改了你的采购草稿提案");
        text.append(orderNo == null ? "并创建了草稿单。" : "，已创建单号为 " + orderNo + " 的草稿单。");
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
    private String failed(PurchaseDraftReceipt receipt) {
        String error = trimToNull(receipt.error());
        if (error == null) {
            throw new IllegalArgumentException("请提供系统拒绝的原因");
        }
        var text = new StringBuilder("用户提交的采购草稿不符合系统生成规则：").append(error).append("。");
        var finalParams = sanitize(receipt.finalParams());
        if (finalParams != null) {
            text.append("\n被拒参数：").append(toJson(finalParams));
        }
        return text.toString();
    }

    /** 用户驳回：说明驳回原因和补充要求。 */
    private String rejected(PurchaseDraftReceipt receipt) {
        var reasons = reasonsOf(receipt);
        String suggestion = trimToNull(receipt.suggestion());
        if (reasons.isEmpty() && suggestion == null) {
            throw new IllegalArgumentException("请说明驳回原因或补充要求");
        }
        var text = new StringBuilder("用户驳回了你的采购草稿。");
        if (!reasons.isEmpty()) {
            text.append("\n驳回原因：").append(String.join("、", reasons));
        }
        if (suggestion != null) {
            text.append("\n补充要求：").append(suggestion);
        }
        return text.toString();
    }

    /** 提取并清洗驳回原因。 */
    private List<String> reasonsOf(PurchaseDraftReceipt receipt) {
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
            throw new IllegalStateException("采购草稿参数序列化失败", error);
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