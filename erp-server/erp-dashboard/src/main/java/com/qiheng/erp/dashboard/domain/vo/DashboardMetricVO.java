package com.qiheng.erp.dashboard.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qiheng.erp.dashboard.domain.enums.MetricKey;
import com.qiheng.erp.dashboard.domain.enums.MetricStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

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

    @Schema(description = "指标稳定编码，不依赖中文展示名称", allowableValues = {"MONTH_SALES", "MONTH_GROSS_PROFIT", "PENDING_ORDERS", "STOCK_RISK_SKU"})
    private MetricKey key;

    @Schema(description = "指标名称，后端按工作台展示口径返回")
    private String label;

    @Schema(description = "指标数值；金额单位为人民币元")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private BigDecimal value;

    @Schema(description = "指标单位，例如 元、单、个")
    private String unit;

    @Schema(description = "对比周期变化率，单位为百分比数值；公式为（本期-上期）/ abs(上期)。上期缺失或为 0 且本期非 0 时返回 null，负数表示原始数值下降")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private BigDecimal changeRate;

    @Schema(description = "对比周期文案，例如 较昨日、较上月同期")
    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String compareText;

    @Schema(description = "指标趋势语义：good / watch / risk / neutral。前端仅用于环比徽标；无可比基线时为 neutral 且不展示徽标", allowableValues = {"good", "watch", "risk", "neutral"})
    private MetricStatus status;
}