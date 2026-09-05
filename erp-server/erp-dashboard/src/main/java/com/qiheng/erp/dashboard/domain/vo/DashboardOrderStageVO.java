package com.qiheng.erp.dashboard.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * <p>
 * 工作台订单流转阶段分布 VO
 * </p>
 *
 * <p>采购单阶段：DRAFT、SUBMITTED、APPROVED、PARTIAL_INBOUND、INBOUND_DONE、CANCELLED；<br>
 * 销售单阶段：DRAFT、SUBMITTED、APPROVED、PARTIAL_OUTBOUND、OUTBOUND_DONE、CANCELLED。<br>
 * 前端按固定 stage 文案展示，后端按当前用户权限裁剪。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@Schema(description = "工作台本自然月订单流转阶段分布")
public class DashboardOrderStageVO {

    @Schema(description = "阶段名称")
    private String stage;

    @Schema(description = "本自然月新建的当前阶段采购单数量；无采购权限时为 0")
    private Integer purchaseCount;

    @Schema(description = "本自然月新建的当前阶段销售单数量；无销售权限时为 0")
    private Integer salesCount;
}