package com.qiheng.erp.ai.order;

import java.util.List;
import java.util.Map;

/**
 * 草稿确认的统一输入：销售、采购只负责把各自的回执映射到这里。
 *
 * @param profile 单据类型文案与字段
 * @param status 处置状态：created/updated/edited/rejected/failed
 * @param create 是否为创建动作
 * @param orderId 单据主键
 * @param orderNo 单据编号
 * @param orderStatus 单据状态编码
 * @param draft 原始草稿参数，用于比较变化字段
 * @param finalParams 用户确认后的最终参数
 * @param userNote 用户修改说明
 * @param reasons 驳回原因
 * @param suggestion 补充要求
 * @param error 系统拒绝原因
 * @author Li
 * @since 2026-11-25
 */
public record OrderDraftConfirmation(
        OrderTypeProfile profile,
        String status,
        boolean create,
        String orderId,
        String orderNo,
        String orderStatus,
        Map<String, Object> draft,
        Map<String, Object> finalParams,
        String userNote,
        List<String> reasons,
        String suggestion,
        String error) {
}