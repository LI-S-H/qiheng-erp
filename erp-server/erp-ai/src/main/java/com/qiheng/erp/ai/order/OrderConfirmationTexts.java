package com.qiheng.erp.ai.order;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确认结果的统一文案：结论先行，参数块只在需要精确数值时追加。
 *
 * @author Li
 * @since 2026-11-25
 */
public final class OrderConfirmationTexts {

    private static final String CREATED = "created";
    private static final String UPDATED = "updated";
    private static final String EDITED = "edited";
    private static final String REJECTED = "rejected";
    private static final String FAILED = "failed";
    private static final String SUBMITTED = "submitted";
    private static final String CANCELLED = "cancelled";

    private OrderConfirmationTexts() {
    }

    /**
     * 生成草稿确认的结论文本。
     *
     * @param confirmation 统一草稿确认
     * @param mapper JSON 序列化组件
     * @return 给模型看的结论文本
     */
    public static String draftText(OrderDraftConfirmation confirmation, ObjectMapper mapper) {
        return switch (confirmation.status()) {
            case CREATED -> createdText(confirmation, mapper);
            case UPDATED -> updatedText(confirmation, mapper);
            case EDITED -> editedText(confirmation, mapper);
            case REJECTED -> rejectedText(confirmation);
            case FAILED -> failedText(confirmation, mapper);
            default -> throw new IllegalArgumentException("不支持的草稿处置状态：" + confirmation.status());
        };
    }

    /**
     * 生成提交或取消确认的结论文本。
     *
     * @param confirmation 统一动作确认
     * @param mapper JSON 序列化组件
     * @return 给模型看的结论文本
     */
    public static String actionText(OrderActionConfirmation confirmation, ObjectMapper mapper) {
        return switch (confirmation.status()) {
            case SUBMITTED -> submittedText(confirmation);
            case CANCELLED -> cancelledText(confirmation);
            case REJECTED -> actionRejectedText(confirmation);
            case FAILED -> actionFailedText(confirmation, mapper);
            default -> throw new IllegalArgumentException("不支持的动作处置状态：" + confirmation.status());
        };
    }

    /**
     * 生成历史卡片需要的展示记录；参数块只保留卡片会读的字段。
     *
     * @param confirmation 统一草稿确认
     * @return 展示记录
     */
    public static Map<String, Object> draftRecord(OrderDraftConfirmation confirmation) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("status", confirmation.status());
        putIfPresent(record, "orderId", confirmation.orderId());
        putIfPresent(record, "orderNo", confirmation.orderNo());
        putIfPresent(record, "orderStatus", confirmation.orderStatus());
        putIfPresent(record, "userNote", confirmation.userNote());
        putIfPresent(record, "suggestion", confirmation.suggestion());
        putIfPresent(record, "error", confirmation.error());

        List<String> reasons = cleanReasons(confirmation.reasons());
        if (!reasons.isEmpty()) {
            record.put("reasons", reasons);
        }

        if (EDITED.equals(confirmation.status()) && confirmation.finalParams() != null) {
            Map<String, Object> finalParams = OrderParams.sanitize(confirmation.finalParams(),
                    confirmation.profile());
            if (finalParams != null) {
                List<String> changedFields = OrderParams.diff(confirmation.draft(), finalParams,
                        confirmation.profile());
                if (!changedFields.isEmpty()) {
                    record.put("changedFields", changedFields);
                }
            }
        }
        return record;
    }

    /**
     * 生成动作历史卡片的展示记录。
     *
     * @param confirmation 统一动作确认
     * @return 展示记录
     */
    public static Map<String, Object> actionRecord(OrderActionConfirmation confirmation) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("status", confirmation.status());
        putIfPresent(record, "orderId", confirmation.orderId());
        putIfPresent(record, "orderNo", confirmation.orderNo());
        putIfPresent(record, "orderStatus", confirmation.orderStatus());
        putIfPresent(record, "error", confirmation.error());
        putIfPresent(record, "reason", confirmation.reason());
        return record;
    }

    /** 创建：用户直接批准模型建议。 */
    private static String createdText(OrderDraftConfirmation confirmation, ObjectMapper mapper) {
        StringBuilder text = new StringBuilder("用户批准了创建，");
        appendOrderReference(text, confirmation.profile(), confirmation.orderNo(), confirmation.orderId());
        text.append(" 已生成草稿。");

        Map<String, Object> block = new LinkedHashMap<>();
        putIfPresent(block, confirmation.profile().idField(), confirmation.orderId());
        putIfPresent(block, "orderNo", confirmation.orderNo());
        if (!block.isEmpty()) {
            text.append("\n单据：").append(toJson(block, mapper));
        }
        return text.toString();
    }

    /** 修改：用户直接批准模型建议，不评价改了什么。 */
    private static String updatedText(OrderDraftConfirmation confirmation, ObjectMapper mapper) {
        StringBuilder text = new StringBuilder("用户批准了修改，");
        appendOrderReference(text, confirmation.profile(), confirmation.orderNo(), confirmation.orderId());
        text.append(" 已保存。");
        appendStatusSentence(text, confirmation.profile(), confirmation.orderStatus());
        return text.toString();
    }

    /** 修改后确认：说明最终参数、用户实际改动和用户说明。 */
    private static String editedText(OrderDraftConfirmation confirmation, ObjectMapper mapper) {
        Map<String, Object> finalParams = OrderParams.sanitize(confirmation.finalParams(),
                confirmation.profile());
        if (finalParams == null) {
            throw new IllegalArgumentException("请提供用户确认后的最终参数");
        }

        StringBuilder text = new StringBuilder();
        if (confirmation.create()) {
            text.append("用户修改了参数并确认创建，");
            appendOrderReference(text, confirmation.profile(), confirmation.orderNo(),
                    confirmation.orderId());
            text.append(" 已生成草稿。");
        } else {
            text.append("用户修改了参数并保存，");
            appendOrderReference(text, confirmation.profile(), confirmation.orderNo(),
                    confirmation.orderId());
            text.append(" 已更新。");
            appendStatusSentence(text, confirmation.profile(), confirmation.orderStatus());
        }

        text.append("\n最终参数：").append(toJson(finalParams, mapper));
        List<String> changes = OrderParams.diff(confirmation.draft(), finalParams,
                confirmation.profile());
        if (!changes.isEmpty()) {
            text.append("\n修改的字段：").append(String.join("、", changes));
        }

        String note = trimToNull(confirmation.userNote());
        if (note != null) {
            text.append("\n用户说明：").append(note);
        }
        return text.toString();
    }

    /** 用户驳回：说明原因和补充要求。 */
    private static String rejectedText(OrderDraftConfirmation confirmation) {
        List<String> reasons = cleanReasons(confirmation.reasons());
        String suggestion = trimToNull(confirmation.suggestion());
        if (reasons.isEmpty() && suggestion == null) {
            throw new IllegalArgumentException("请说明驳回原因或补充要求");
        }

        StringBuilder text = new StringBuilder();
        if (confirmation.create()) {
            text.append("用户驳回了这次创建，未生成").append(confirmation.profile().orderName())
                    .append("。");
        } else {
            text.append("用户驳回了这次修改，");
            appendOrderReference(text, confirmation.profile(), confirmation.orderNo(),
                    confirmation.orderId());
            text.append(" 保持原样。");
        }

        if (!reasons.isEmpty()) {
            text.append("\n").append(confirmation.create() ? "驳回原因：" : "原因：")
                    .append(String.join("、", reasons));
        }
        if (suggestion != null) {
            text.append("\n补充要求：").append(suggestion);
        }
        return text.toString();
    }

    /** 系统拒绝：只说明规则原因和被拒参数。 */
    private static String failedText(OrderDraftConfirmation confirmation, ObjectMapper mapper) {
        String error = trimToNull(confirmation.error());
        if (error == null) {
            throw new IllegalArgumentException("请提供系统拒绝的原因");
        }

        StringBuilder text = new StringBuilder();
        if (confirmation.create()) {
            text.append("创建未通过，未生成").append(confirmation.profile().orderName())
                    .append("：").append(error).append("。");
        } else {
            appendOrderReference(text, confirmation.profile(), confirmation.orderNo(),
                    confirmation.orderId());
            text.append(" 修改未保存：").append(error).append("。");
        }

        Map<String, Object> finalParams = OrderParams.sanitize(confirmation.finalParams(),
                confirmation.profile());
        if (finalParams != null) {
            text.append("\n被拒参数：").append(toJson(finalParams, mapper));
        }
        return text.toString();
    }

    /** 用户同意提交。 */
    private static String submittedText(OrderActionConfirmation confirmation) {
        StringBuilder text = new StringBuilder("用户同意提交，");
        appendOrderReference(text, confirmation.profile(), confirmation.orderNo(), confirmation.orderId());
        text.append(" 已提交审核。");

        String status = confirmation.profile().statusText(confirmation.orderStatus());
        text.append("当前状态为").append(status == null ? "待审核" : status).append("。");

        String extraValue = trimToNull(confirmation.extraValue());
        if (extraValue != null) {
            text.append("\n").append(confirmation.extraLabel()).append("：").append(extraValue);
        }
        return text.toString();
    }

    /** 用户同意取消。 */
    private static String cancelledText(OrderActionConfirmation confirmation) {
        StringBuilder text = new StringBuilder("用户同意取消，");
        appendOrderReference(text, confirmation.profile(), confirmation.orderNo(), confirmation.orderId());
        text.append(" 已取消。");

        String reason = trimToNull(confirmation.reason());
        if (reason != null) {
            text.append("\n原因：").append(reason);
        }
        return text.toString();
    }

    /** 用户暂不执行。 */
    private static String actionRejectedText(OrderActionConfirmation confirmation) {
        StringBuilder text = new StringBuilder("用户暂不").append(confirmation.actionLabel()).append("，");
        appendOrderReference(text, confirmation.profile(), confirmation.orderNo(), confirmation.orderId());
        text.append(" 保持")
                .append("提交".equals(confirmation.actionLabel()) ? "草稿状态" : "当前状态")
                .append("。");

        String reason = trimToNull(confirmation.reason());
        if (reason != null) {
            text.append("\n原因：").append(reason);
        }
        return text.toString();
    }

    /** 系统拒绝提交或取消。 */
    private static String actionFailedText(OrderActionConfirmation confirmation, ObjectMapper mapper) {
        String error = trimToNull(confirmation.error());
        if (error == null) {
            throw new IllegalArgumentException("请提供系统拒绝的原因");
        }

        StringBuilder text = new StringBuilder();
        appendOrderReference(text, confirmation.profile(), confirmation.orderNo(), confirmation.orderId());
        text.append("提交".equals(confirmation.actionLabel()) ? " 提交未通过：" : " 取消失败：")
                .append(error).append("。");

        Map<String, Object> orderParams = confirmation.orderParams();
        if (orderParams != null && !orderParams.isEmpty()) {
            text.append("\n当前单据：").append(toJson(orderParams, mapper));
        }
        return text.toString();
    }

    /** 追加“单据名 + 单号/ID”；没有标识时只写单据名。 */
    private static void appendOrderReference(StringBuilder text, OrderTypeProfile profile,
                                             String orderNo, String orderId) {
        text.append(profile.orderName());
        String label = orderLabel(orderNo, orderId);
        if (label != null) {
            text.append(" ").append(label);
        }
    }

    /** 追加当前状态；状态为空时跳过。 */
    private static void appendStatusSentence(StringBuilder text, OrderTypeProfile profile, String status) {
        String statusText = profile.statusText(status);
        if (statusText != null) {
            text.append("当前状态为").append(statusText).append("。");
        }
    }

    /** 单号优先，其次 ID。 */
    private static String orderLabel(String orderNo, String orderId) {
        String no = trimToNull(orderNo);
        if (no != null) {
            return no;
        }
        String id = trimToNull(orderId);
        return id == null ? null : "ID：" + id;
    }

    /** 清洗驳回原因。 */
    private static List<String> cleanReasons(List<String> reasons) {
        if (reasons == null) {
            return List.of();
        }
        return reasons.stream()
                .map(OrderConfirmationTexts::trimToNull)
                .filter(reason -> reason != null)
                .distinct()
                .toList();
    }

    /** 只在有值时写入字段。 */
    private static void putIfPresent(Map<String, Object> target, String field, String value) {
        String text = trimToNull(value);
        if (text != null) {
            target.put(field, text);
        }
    }

    /** 去掉首尾空格；空字符串转 null。 */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 把参数序列化成单行 JSON。 */
    private static String toJson(Map<String, Object> params, ObjectMapper mapper) {
        try {
            return mapper.writeValueAsString(params);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("单据参数序列化失败", error);
        }
    }
}