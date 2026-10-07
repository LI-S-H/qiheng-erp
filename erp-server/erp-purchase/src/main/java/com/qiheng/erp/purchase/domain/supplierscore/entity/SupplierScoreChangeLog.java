package com.qiheng.erp.purchase.domain.supplierscore.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/** 评分变化日志；推荐分、总分是随指标变化记录的前后快照，而非独立指标类型。 */
@Data
@TableName("supplier_score_change_log")
@Schema(description = "评分变化日志")
public class SupplierScoreChangeLog {
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @TableField("change_key")
    @Schema(description = "变化键")
    private String changeKey;

    @TableField("supplier_id")
    @Schema(description = "供应商ID")
    private Long supplierId;

    @TableField("supplier_product_id")
    @Schema(description = "供应商产品ID")
    private Long supplierProductId;

    @TableField("metric_type")
    @Schema(description = "指标类型")
    private String metricType;

    @TableField("metric_score_before")
    @Schema(description = "指标变化前的评分")
    private Integer metricScoreBefore;

    @TableField("metric_score_after")
    @Schema(description = "指标变化后的评分")
    private Integer metricScoreAfter;

    @TableField("product_recommend_score_before")
    @Schema(description = "产品推荐分变化前的评分")
    private Integer productRecommendScoreBefore;

    @TableField("product_recommend_score_after")
    @Schema(description = "产品推荐分变化后的评分")
    private Integer productRecommendScoreAfter;

    @TableField("supplier_overall_score_before")
    @Schema(description = "供应商总分变化前的评分")
    private Integer supplierOverallScoreBefore;

    @TableField("supplier_overall_score_after")
    @Schema(description = "供应商总分变化后的评分")
    private Integer supplierOverallScoreAfter;

    @TableField("trigger_type")
    @Schema(description = "触发类型")
    private String triggerType;

    @TableField("batch_no")
    @Schema(description = "重算批次号")
    private String batchNo;

    @TableField("rule_version")
    @Schema(description = "规则版本")
    private String ruleVersion;

    @TableField("related_sources")
    @Schema(description = "完整业务来源JSON数组，ID与编号属于同一业务对象")
    private String relatedSources;

    @TableField("operator_type")
    @Schema(description = "操作人类型:USER-人工操作,SYSTEM-系统任务")
    private String operatorType;

    @TableField("operator_id")
    @Schema(description = "操作人ID;SYSTEM 类型时为 NULL")
    private Long operatorId;

    @TableField("operator_name")
    @Schema(description = "操作人姓名;USER 类型填姓名,SYSTEM 类型填场景描述")
    private String operatorName;

    @TableField("reason")
    @Schema(description = "操作原因")
    private String reason;

    @TableField("create_time")
    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
