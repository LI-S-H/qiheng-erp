package com.qiheng.erp.dashboard.domain.trend.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 经营趋势可见维度标记。
 *
 * <p>由后端按当前用户的 {@code sales:query} / {@code purchase:query}
 * 计算下发；前端根据本对象决定哪些 series 渲染、是否整卡降级。
 * 两边都不可见时 {@code canViewGross} 必为 false。</p>
 *
 * @author Li
 * @since 2026-08-29
 */
@Data
@Schema(description = "经营趋势可见维度标记")
public class DashboardTrendPermissionsVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "是否可见销售曲线；对应 sales:query 权限")
    private Boolean canViewSales;

    @Schema(description = "是否可见采购曲线；对应 purchase:query 权限")
    private Boolean canViewPurchase;

    @Schema(description = "是否可见毛利曲线；同时需要销售与采购 query 才为 true")
    private Boolean canViewGross;
}