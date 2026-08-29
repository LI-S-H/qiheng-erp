package com.qiheng.erp.dashboard.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 订单流转可见维度标记。
 *
 * <p>由后端按当前用户的 {@code purchase:query} / {@code sales:query}
 * 计算下发；前端根据本对象决定图例、计数和条形按列裁剪；
 * 两边都不可见时整卡降级为空状态。</p>
 *
 * @author Li
 * @since 2026-08-29
 */
@Data
@Schema(description = "订单流转可见维度标记")
public class DashboardOrderStagePermissionsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "是否可见采购订单流转；对应 purchase:query 权限")
    private Boolean canViewPurchase;

    @Schema(description = "是否可见销售订单流转；对应 sales:query 权限")
    private Boolean canViewSales;
}
