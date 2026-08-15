package com.qiheng.erp.dashboard.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * <p>
 * 工作台待办证据附加指标 VO
 * </p>
 *
 * <p>金额、百分比和天数由后端按工作台口径返回，前端不再二次格式化。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@Schema(description = "工作台待办证据附加指标")
public class DashboardTodoEvidenceMetricVO {

    @Schema(description = "指标名称")
    private String label;

    @Schema(description = "已格式化展示值")
    private String value;

    @Schema(description = "指标风险色语义", allowableValues = {"neutral", "watch", "risk"})
    private String tone;
}