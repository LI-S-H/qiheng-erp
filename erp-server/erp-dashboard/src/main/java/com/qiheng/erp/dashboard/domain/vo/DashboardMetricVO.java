package com.qiheng.erp.dashboard.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * <p>
 * 工作台首屏经营指标 VO
 * </p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@Schema(description = "工作台首屏经营指标")
public class DashboardMetricVO {

    @Schema(description = "指标名称，后端按工作台展示口径返回")
    private String label;

    @Schema(description = "指标数值；金额单位为人民币元")
    private Double value;

    @Schema(description = "指标单位，例如 元、单、个")
    private String unit;

    @Schema(description = "对比周期变化率，单位为百分比数值；负数表示下降")
    private Double changeRate;

    @Schema(description = "对比周期文案，例如 较昨日、较上月同期")
    private String compareText;

    @Schema(description = "指标风险色语义：good / watch / risk / neutral", allowableValues = {"good", "watch", "risk", "neutral"})
    private String status;
}