package com.qiheng.erp.ai.order;

import java.util.Map;

/**
 * 提交或取消的统一输入：三种结果共用一套文案模板。
 *
 * @param profile 单据类型文案与字段
 * @param status 处置状态：submitted/cancelled/rejected/failed
 * @param orderId 单据主键
 * @param orderNo 单据编号
 * @param orderStatus 单据状态编码
 * @param error 系统拒绝原因
 * @param reason 用户原因（取消原因或暂不执行原因）
 * @param actionLabel 动作名称：提交/取消
 * @param extraLabel 附加日期名称，例如“预计发货”
 * @param extraValue 附加日期值
 * @param orderParams 系统拒绝时的当前单据参数
 * @author Li
 * @since 2026-11-25
 */
public record OrderActionConfirmation(
        OrderTypeProfile profile,
        String status,
        String orderId,
        String orderNo,
        String orderStatus,
        String error,
        String reason,
        String actionLabel,
        String extraLabel,
        String extraValue,
        Map<String, Object> orderParams) {
}