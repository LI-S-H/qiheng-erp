package com.qiheng.erp.dashboard.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

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

    @Schema(description = "待办来源方式", allowableValues = {"AGGREGATED", "PERSISTED"})
    private String sourceMode;

    @Schema(description = "完成方式", allowableValues = {"AUTO", "TRACKED"})
    private String completionMode;

    @Schema(description = "待办状态", allowableValues = {"PENDING", "DONE", "IGNORED"})
    private String status;

    @Schema(description = "异常类待办错误码；非异常待办返回 null")
    private String errorCode;

    @Schema(description = "异常类待办错误信息")
    private String errorMessage;

    @Schema(description = "来源业务单号或外部事件编号")
    private String sourceNo;

    @Schema(description = "待办发生时间或最后触发时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime occurredAt;

    @Schema(description = "处理建议")
    private String resolveHint;

    @Schema(description = "代表性单据摘要证据列表")
    private List<DashboardTodoEvidenceVO> evidence = new ArrayList<>();

    @Schema(description = "点击待办跳转的业务路由")
    private String route;
}