package com.qiheng.erp.ai.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.domain.vo.ChatMessageVO;
import com.qiheng.erp.ai.domain.vo.VisibleMessageContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 按消息类型转换当前会话流水，不暴露其他工具的内部信息。
 *
 * @author Li
 * @since 2026-10-27
 */
@Service
@RequiredArgsConstructor
public class VisibleMessageCodec {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ObjectMapper mapper;

    /**
     * 序列化展示消息。
     *
     * @param content 展示消息内容
     * @return 序列化后的 JSON
     */
    public String encode(VisibleMessageContent content) {
        try {
            return mapper.writeValueAsString(content);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("展示消息序列化失败", error);
        }
    }

    /**
     * 反序列化展示消息。
     *
     * @param raw 序列化的消息内容
     * @return 展示消息内容
     */
    public VisibleMessageContent decode(String raw) {
        return decode(raw, null);
    }

    /** 判断是否采购单动作记录，并按动作校验状态取值。 */
    private static boolean isPurchaseOrderRecord(String name, String status) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return switch (name) {
            case "prepare_purchase_order_draft", "update_purchase_order_draft" ->
                    Set.of("created", "updated", "edited", "failed", "rejected").contains(status);
            case "submit_purchase_order" ->
                    Set.of("submitted", "rejected", "failed").contains(status);
            case "cancel_purchase_order" ->
                    Set.of("cancelled", "rejected", "failed").contains(status);
            default -> false;
        };
    }

    /** 判断是否销售单动作记录，并按动作校验状态取值。 */
    private static boolean isSalesOrderRecord(String name, String status) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return switch (name) {
            case "prepare_sales_order_draft", "update_sales_order_draft" ->
                    Set.of("created", "updated", "edited", "failed", "rejected").contains(status);
            case "submit_sales_order" ->
                    Set.of("submitted", "rejected", "failed").contains(status);
            case "cancel_sales_order" ->
                    Set.of("cancelled", "rejected", "failed").contains(status);
            default -> false;
        };
    }

    /**
     * 读取当前消息及已保存的普通文本、追问工具消息。
     *
     * @param raw 序列化的消息内容
     * @param kind 消息种类；为空表示按普通可见消息处理
     * @return 展示消息内容
     */
    private VisibleMessageContent decode(String raw, String kind) {
        if (raw == null || raw.isBlank()) {
            return text("");
        }
        try {
            JsonNode root = mapper.readTree(raw);
            if (root == null || root.isNull()) {
                return text("");
            }
            // 早期流水采用 type/data 包装，保留普通文本和 ask_user 历史。
            if (root.isObject() && root.path("data").isObject()) {
                String type = root.path("type").asText();
                if ("TEXT".equals(type)) {
                    return text(root.path("data").path("text").asText(""));
                }
                if (("TOOL_CALL".equals(type) || "TOOL_RESULT".equals(type)) && type.equals(kind)) {
                    root = root.path("data");
                }
            }
            if (("TOOL_CALL".equals(kind) || "TOOL_RESULT".equals(kind)) && root.isObject()) {
                if ("TOOL_CALL".equals(kind) && isPurchaseOrderRecord(root.path("name").asText(),
                        root.path("status").asText())) {
                    // 采购单动作记录：只保留状态、单号与结果，明细由页面按单号查采购单。
                    Map<String, Object> data = new LinkedHashMap<>();
                    for (String field : Set.of("action", "status", "purchaseOrderId", "orderNo", "orderStatus",
                            "changedFields", "userNote", "reasons", "suggestion", "error", "reason")) {
                        if (root.has(field)) {
                            data.put(field, mapper.convertValue(root.get(field), Object.class));
                        }
                    }
                    return new VisibleMessageContent("PURCHASE_ORDER", data);
                }
                if ("TOOL_CALL".equals(kind) && isSalesOrderRecord(root.path("name").asText(),
                        root.path("status").asText())) {
                    // 销售单动作记录：与采购单一样只保存动作、状态和单号。
                    Map<String, Object> data = new LinkedHashMap<>();
                    for (String field : Set.of("action", "status", "salesOrderId", "orderNo", "orderStatus",
                            "changedFields", "userNote", "reasons", "suggestion", "error", "reason")) {
                        if (root.has(field)) {
                            data.put(field, mapper.convertValue(root.get(field), Object.class));
                        }
                    }
                    return new VisibleMessageContent("SALES_ORDER", data);
                }
                if (!"ask_user".equals(root.path("name").asText())) {
                    // 普通工具行是结构化展示：只给工具名、文案、状态与耗时，不含参数和结果。
                    if ("TOOL_CALL".equals(kind) && root.has("label")) {
                        Map<String, Object> data = new LinkedHashMap<>();
                        for (String field : Set.of("callId", "name", "label", "status", "durationMs")) {
                            if (root.has(field)) {
                                data.put(field, mapper.convertValue(root.get(field), Object.class));
                            }
                        }
                        return new VisibleMessageContent("TOOL_CALL", data);
                    }
                    return text("");
                }
                Set<String> fields = "TOOL_CALL".equals(kind)
                        ? Set.of("callId", "name", "arguments") : Set.of("callId", "name", "result");
                Map<String, Object> data = new LinkedHashMap<>();
                for (String field : fields) {
                    if (root.has(field)) {
                        data.put(field, mapper.convertValue(root.get(field), Object.class));
                    }
                }
                return new VisibleMessageContent(kind, data);
            }
            if ("ANALYSIS_STEP".equals(kind) && root.isObject()) {
                // 分析过程整块回看；字段与实时事件保持一致。
                Map<String, Object> data = new LinkedHashMap<>();
                for (String field : Set.of("toolCallId", "goal", "steps", "completion", "summary")) {
                    if (root.has(field)) {
                        data.put(field, mapper.convertValue(root.get(field), Object.class));
                    }
                }
                return new VisibleMessageContent("ANALYSIS", data);
            }
            if ("REPORT_STEP".equals(kind) && root.isObject()) {
                Map<String, Object> data = new LinkedHashMap<>();
                for (String field : Set.of("toolCallId", "taskId", "status", "steps", "report")) {
                    if (root.has(field)) {
                        data.put(field, mapper.convertValue(root.get(field), Object.class));
                    }
                }
                return new VisibleMessageContent("REPORT", data);
            }
            if (root.isObject() && !root.has("type") && root.path("text").isTextual()) {
                return text(root.path("text").asText());
            }
            return text(root.isTextual() ? root.asText() : "该历史消息暂时无法展示。");
        } catch (JsonProcessingException error) {
            // 无法解释的旧 JSON 不暴露原始对象；历史纯文本仍可正常显示。
            String trimmed = raw.stripLeading();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return text("该历史消息暂时无法展示。");
            }
            return text(raw);
        }
    }

    /**
     * 构造纯文本展示消息。
     *
     * @param text 文本内容
     * @return 展示消息内容
     */
    public VisibleMessageContent text(String text) {
        return new VisibleMessageContent("TEXT", Map.of("text", text));
    }

    /**
     * 转换为视图模型。
     *
     * @param message 数据库消息
     * @return 视图模型
     */
    public ChatMessageVO toView(AiMessage message) {
        return new ChatMessageVO(string(message.getId()), message.getRequestId() == null ? "" : message.getRequestId(),
                string(message.getSeq()),
                message.getRole(), message.getKind(), decode(message.getContent(), message.getKind()),
                message.getCreateTime() == null ? null : message.getCreateTime().format(TIME));
    }

    /** 转换为字符串；空值保持为空。 */
    private String string(Long value) {
        return value == null ? null : value.toString();
    }
}
