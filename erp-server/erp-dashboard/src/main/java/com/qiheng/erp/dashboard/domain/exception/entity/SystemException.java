package com.qiheng.erp.dashboard.domain.exception.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 系统异常记录表实体
 * </p>
 *
 * <p>对应 {@code system_exception} 表，工作台聚合待办和后续异常中心复用。
 * 表由 AI 模块、MQ 消费器、第三方回调处理、定时任务执行和数据补偿任务统一写入。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@Accessors(chain = true)
@TableName("system_exception")
@Schema(name = "SystemException", description = "系统异常记录")
public class SystemException implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "系统异常记录 ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "稳定异常编号，例如 AI-MCP-20260701-001")
    @TableField("exception_no")
    private String exceptionNo;

    @Schema(description = "异常类型：MCP_TOOL_FAILED / MQ_DEAD_LETTER / EXT_CALLBACK_FAILED / JOB_FAILED / COMPENSATION_FAILED")
    @TableField("exception_type")
    private String exceptionType;

    @Schema(description = "来源模块：AI / MQ / LOGISTICS / SCHEDULER 等")
    @TableField("source_module")
    private String sourceModule;

    @Schema(description = "来源业务单号、消息 ID、任务编码或第三方回调幂等键")
    @TableField("source_no")
    private String sourceNo;

    @Schema(description = "严重级别：HIGH / MEDIUM / LOW")
    @TableField("severity")
    private String severity;

    @Schema(description = "错误码")
    @TableField("error_code")
    private String errorCode;

    @Schema(description = "错误信息")
    @TableField("error_message")
    private String errorMessage;

    @Schema(description = "工作台可展示的问题摘要")
    @TableField("detail_summary")
    private String detailSummary;

    @Schema(description = "处理建议")
    @TableField("resolve_hint")
    private String resolveHint;

    @Schema(description = "状态：PENDING / PROCESSING / RESOLVED / IGNORED")
    @TableField("status")
    private String status;

    @Schema(description = "异常发生时间")
    @TableField("occurred_at")
    private LocalDateTime occurredAt;

    @Schema(description = "最近重试时间")
    @TableField("last_retry_at")
    private LocalDateTime lastRetryAt;

    @Schema(description = "处理人 ID")
    @TableField("handled_by")
    private Long handledBy;

    @Schema(description = "处理时间")
    @TableField("handled_at")
    private LocalDateTime handledAt;

    @Schema(description = "处理备注")
    @TableField("handle_remark")
    private String handleRemark;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;
}