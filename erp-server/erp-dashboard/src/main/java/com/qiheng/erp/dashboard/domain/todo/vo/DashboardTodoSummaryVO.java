package com.qiheng.erp.dashboard.domain.todo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 工作台待办的轻量摘要，仅用于铃铛等不展示详情证据的入口。 */
@Data
@Schema(description = "工作台待办轻量摘要")
public class DashboardTodoSummaryVO {
    @Schema(description = "待办项稳定标识")
    private String todoId;

    @Schema(description = "待办所属业务域或事件源编码")
    private String businessType;

    @Schema(description = "待办类型展示标签")
    private String businessLabel;

    @Schema(description = "待办标题")
    private String title;

    @Schema(description = "待办业务影响说明")
    private String description;

    @Schema(description = "待处理数量")
    private Integer count;

    @Schema(description = "优先级", allowableValues = {"HIGH", "MEDIUM", "LOW"})
    private String priority;
}