package com.qiheng.erp.ai.returnorder;

import com.qiheng.erp.returnorder.domain.vo.ReturnOrderItemVo;
import com.qiheng.erp.returnorder.domain.vo.ReturnableSourceDetailVo;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 退货草稿预检：在把草稿交给用户确认前，用只读查询拿到的可退事实做结构与业务校验。
 *
 * <p>只读、不加锁、不写库；通过后仍由业务接口做最终校验。</p>
 *
 * @author Li
 * @since 2026-11-24
 */
public final class ReturnDraftPrecheck {

    private static final Set<String> SALES_STATUSES = Set.of("PARTIAL_OUTBOUND", "OUTBOUND_DONE");
    private static final Set<String> PURCHASE_STATUSES = Set.of("PARTIAL_INBOUND", "INBOUND_DONE");
    private static final Set<String> HANDLING_TYPES = Set.of("REFUND", "EXCHANGE", "OTHER");
    private static final Set<String> REASON_CODES = Set.of("QUALITY_ISSUE", "DAMAGED", "WRONG_ITEM",
            "QUANTITY_ERROR", "SPEC_MISMATCH", "NO_LONGER_NEEDED", "OTHER");

    /**
     * 校验草稿参数；通过时返回 null，不通过时返回给模型修正的错误文本。
     *
     * @param request 草稿参数
     * @param source 只读查询得到的来源单与可退明细
     * @return 错误文本或 null
     */
    public String check(ReturnOrderDraftRequest request, ReturnableSourceDetailVo source) {
        if (request == null) {
            return "退货草稿参数有误：缺少草稿参数，请补充后重试。";
        }
        if (source == null) {
            return "退货草稿参数有误：没有取到来源单信息，请先重新查询可退明细。";
        }
        String type = request.returnType();
        if (!"SALES_RETURN".equals(type) && !"PURCHASE_RETURN".equals(type)) {
            return "退货草稿参数有误：returnType 只能是 SALES_RETURN 或 PURCHASE_RETURN。";
        }
        if (!type.equals(source.getReturnType())) {
            return "退货类型与来源单不一致：草稿 " + type + "，来源单 " + source.getReturnType() + "。";
        }
        Set<String> allowedStatuses = "SALES_RETURN".equals(type) ? SALES_STATUSES : PURCHASE_STATUSES;
        if (!allowedStatuses.contains(source.getSourceOrderStatus())) {
            String hint = "SALES_RETURN".equals(type) ? "部分出库或全部出库的销售单" : "部分入库或全部入库的采购单";
            return "来源单状态 " + source.getSourceOrderStatus() + " 不允许退货，仅" + hint + "可以退货。";
        }
        if (!same(request.sourceOrderId(), source.getSourceOrderId())) {
            return "来源订单与草稿不一致，请重新查询来源单。";
        }
        if (!same(request.warehouseId(), source.getWarehouseId())) {
            return "退货仓库必须与原订单仓库一致，不能换仓退货。";
        }
        String headerError = checkHeader(request);
        if (headerError != null) {
            return headerError;
        }
        return checkItems(request.items(), source.getItems());
    }

    /** 校验表头字段：处理方式、原因编码、原因说明、执行日期。 */
    private String checkHeader(ReturnOrderDraftRequest request) {
        String handling = request.handlingType() == null ? "REFUND" : request.handlingType();
        if (!HANDLING_TYPES.contains(handling)) {
            return "退货草稿参数有误：处理方式不合法，只能是 REFUND、EXCHANGE 或 OTHER。";
        }
        String reasonCode = request.reasonCode();
        if (reasonCode == null || !REASON_CODES.contains(reasonCode)) {
            return "退货草稿参数有误：退货原因编码不合法，只能从 7 个枚举中选择。";
        }
        String reasonText = request.returnReason() == null ? "" : request.returnReason().trim();
        if ("OTHER".equals(reasonCode) && reasonText.isEmpty()) {
            return "退货草稿参数有误：选择其他原因时必须填写具体说明。";
        }
        if (reasonText.isEmpty()) {
            return "退货草稿参数有误：退货原因说明不能为空。";
        }
        String date = request.expectedExecutionDate();
        if (date != null && !date.isBlank()) {
            try {
                if (LocalDate.parse(date.trim()).isBefore(LocalDate.now())) {
                    return "退货草稿参数有误：预计执行日期不得早于当天。";
                }
            } catch (DateTimeParseException error) {
                return "退货草稿参数有误：预计执行日期格式应为 yyyy-MM-dd。";
            }
        }
        return null;
    }

    /** 校验明细：归属、重复、数量、精度与可退上限。 */
    private String checkItems(List<ReturnOrderDraftRequest.Item> items, List<ReturnOrderItemVo> sourceItems) {
        if (items == null || items.isEmpty()) {
            return "退货草稿参数有误：items 至少需要一行退货明细。";
        }
        Map<String, ReturnOrderItemVo> sourceMap = new HashMap<>();
        if (sourceItems != null) {
            for (ReturnOrderItemVo item : sourceItems) {
                sourceMap.put(String.valueOf(item.getSourceOrderItemId()), item);
            }
        }
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < items.size(); index++) {
            ReturnOrderDraftRequest.Item item = items.get(index);
            int row = index + 1;
            if (item == null || item.sourceOrderItemId() == null || item.sourceOrderItemId().isBlank()) {
                return "退货草稿参数有误：第 " + row + " 行来源明细不能为空。";
            }
            String itemId = item.sourceOrderItemId().trim();
            if (!seen.add(itemId)) {
                return "退货草稿参数有误：退货明细重复，来源明细 " + itemId + " 只能出现一次。";
            }
            ReturnOrderItemVo sourceItem = sourceMap.get(itemId);
            if (sourceItem == null) {
                return "退货草稿参数有误：第 " + row + " 行来源明细 " + itemId + " 不属于来源订单。";
            }
            BigDecimal qty = item.requestedQty();
            if (qty == null || qty.signum() <= 0) {
                return "退货草稿参数有误：第 " + row + " 行数量必须大于 0。";
            }
            int precision = sourceItem.getQuantityPrecision() == null ? 0 : sourceItem.getQuantityPrecision();
            if (qty.stripTrailingZeros().scale() > precision) {
                return "退货草稿参数有误：第 " + row + " 行数量最多保留 " + precision + " 位小数。";
            }
            BigDecimal available = sourceItem.getAvailableReturnQty() == null
                    ? BigDecimal.ZERO : sourceItem.getAvailableReturnQty();
            if (qty.compareTo(available) > 0) {
                return "退货草稿参数有误：第 " + row + " 行数量 " + plain(qty)
                        + " 超过可退 " + plain(available) + "。";
            }
        }
        return null;
    }

    /** 比较字符串与数字 ID。 */
    private boolean same(String requestValue, Object sourceValue) {
        return Objects.equals(requestValue == null ? null : requestValue.trim(), String.valueOf(sourceValue));
    }

    /** 去掉多余小数位，便于错误提示阅读。 */
    private String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}