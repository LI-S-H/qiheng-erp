package com.qiheng.erp.ai.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 单据参数清洗与变化比较：只保留业务字段，数字按数值比较。
 *
 * @author Li
 * @since 2026-11-25
 */
public final class OrderParams {

    private OrderParams() {
    }

    /**
     * 过滤前端派生字段，只保留工具参数结构内的业务字段。
     *
     * @param params 原始参数
     * @param profile 单据类型
     * @return 清洗后的参数；缺少有效明细时返回 null
     */
    public static Map<String, Object> sanitize(Map<String, Object> params, OrderTypeProfile profile) {
        if (params == null) {
            return null;
        }

        Map<String, Object> target = new LinkedHashMap<>();
        for (String field : profile.headerFields()) {
            target.put(field, normalizeValue(field, params.get(field)));
        }

        if (!(params.get("items") instanceof List<?> lines) || lines.isEmpty()) {
            return null;
        }

        List<Map<String, Object>> items = new ArrayList<>();
        for (Object line : lines) {
            if (!(line instanceof Map<?, ?> source)) {
                return null;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            for (String field : profile.itemFields()) {
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

    /**
     * 比较草稿与最终参数，返回发生变化的字段路径。
     *
     * @param draft 原始草稿
     * @param finalParams 最终参数
     * @param profile 单据类型
     * @return 变化字段路径
     */
    public static List<String> diff(Map<String, Object> draft, Map<String, Object> finalParams,
                                    OrderTypeProfile profile) {
        List<String> changes = new ArrayList<>();
        for (String field : profile.headerFields()) {
            if (!sameValue(draft.get(field), finalParams.get(field))) {
                changes.add(field);
            }
        }

        List<?> before = draft.get("items") instanceof List<?> lines ? lines : List.of();
        List<?> after = finalParams.get("items") instanceof List<?> lines ? lines : List.of();
        if (before.isEmpty()) {
            return changes;
        }
        if (before.size() != after.size()) {
            changes.add("items");
            return changes;
        }

        for (int index = 0; index < before.size(); index++) {
            if (!(before.get(index) instanceof Map<?, ?> source)
                    || !(after.get(index) instanceof Map<?, ?> target)) {
                changes.add("items[" + index + "]");
                continue;
            }

            for (String field : profile.itemFields()) {
                if (!sameValue(source.get(field), target.get(field))) {
                    changes.add("items[" + index + "]." + field);
                }
            }
        }
        return changes;
    }

    /** 统一字段的展示形式：数量/单价转 BigDecimal，其他字段去空格。 */
    private static Object normalizeValue(String field, Object value) {
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

    /** 比较两个值；数字按数值比较，避免 100 与 100.00 被当成变化。 */
    private static boolean sameValue(Object left, Object right) {
        Object one = normalizeValue("quantity", left);
        Object two = normalizeValue("quantity", right);
        if (one instanceof BigDecimal first && two instanceof BigDecimal second) {
            return first.compareTo(second) == 0;
        }
        return Objects.equals(one, two);
    }
}