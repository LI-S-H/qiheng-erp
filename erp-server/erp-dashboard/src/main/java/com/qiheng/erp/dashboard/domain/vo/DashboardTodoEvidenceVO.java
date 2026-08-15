package com.qiheng.erp.dashboard.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 * 工作台待办代表性单据摘要证据 VO
 * </p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@Schema(description = "工作台待办代表性单据摘要证据")
public class DashboardTodoEvidenceVO {

    @Schema(description = "证据项稳定标识，例如单据号或异常编号")
    private String itemId;

    @Schema(description = "证据主标题，例如 SO20260630033")
    private String primaryText;

    @Schema(description = "证据副标题/上下文说明")
    private String secondaryText;

    @Schema(description = "证据附加指标列表，用于在卡片中展示金额、品项、等待时长等")
    private List<DashboardTodoEvidenceMetricVO> metrics = new ArrayList<>();
}