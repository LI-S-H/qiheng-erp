package com.qiheng.erp.dashboard.domain.todo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;


/**
 * <p>
 * 工作台待办项 VO
 * </p>
 *
 * <p>继承自 {@link DashboardTodoSummaryVO}，父类承载铃铛与工作台共用的 7 个基础字段，
 * 子类补充工作台详情所需的排序权重、完成方式、处理建议和详情证据。
 * 前端铃铛只读父类字段，工作台读完整字段，继承关系保证两端字段定义同源。</p>
 *
 * <p>待办排序由后端完成并返回 sortWeight，前端只做兜底排序。普通业务待办使用 {@code completionMode=AUTO}，
 * 系统异常使用 {@code completionMode=TRACKED}。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "工作台待办项")
public class DashboardTodoItemVO extends DashboardTodoSummaryVO {

    @Schema(description = "排序权重，数值越小越靠前")
    private Integer sortWeight;


    @Schema(description = "完成方式", allowableValues = {"AUTO", "TRACKED"})
    private String completionMode;


    @Schema(description = "处理建议")
    private String resolveHint;


    @Schema(description = "按模型组织的待办详情", requiredMode = Schema.RequiredMode.REQUIRED)
    private DashboardTodoDetailVO detail;
}