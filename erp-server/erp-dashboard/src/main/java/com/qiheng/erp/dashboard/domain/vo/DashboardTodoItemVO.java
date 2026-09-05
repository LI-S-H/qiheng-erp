package com.qiheng.erp.dashboard.domain.vo;

import com.qiheng.erp.dashboard.domain.vo.todo.DashboardTodoDetailVO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;


/**
 * <p>
 * 工作台待办项 VO
 * </p>
 *
 * <p>待办排序由后端完成并返回 sortWeight，前端只做兜底排序。普通业务待办使用 {@code completionMode=AUTO}，
 * 系统异常使用 {@code completionMode=TRACKED}。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@Schema(description = "工作台待办项")
public class DashboardTodoItemVO {

    @Schema(description = "待办项稳定标识")
    private String todoId;

    @Schema(description = "待办所属业务域或事件源编码，建议使用大写下划线编码")
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

    @Schema(description = "排序权重，数值越小越靠前")
    private Integer sortWeight;


    @Schema(description = "完成方式", allowableValues = {"AUTO", "TRACKED"})
    private String completionMode;


    @Schema(description = "处理建议")
    private String resolveHint;


    @Schema(description = "按模型组织的待办详情", requiredMode = Schema.RequiredMode.REQUIRED)
    private DashboardTodoDetailVO detail;
}