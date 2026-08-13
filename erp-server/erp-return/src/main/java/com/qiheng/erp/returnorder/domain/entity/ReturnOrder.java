package com.qiheng.erp.returnorder.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 统一退货单主表。
 *
 * <p>采购退货和销售退货共用本表，{@code returnType} 记录业务方向；供应商或客户、
 * 仓库和商品名称均保存创建时快照，避免后续基础资料变更影响历史单据。</p>
 */
@Data
@Accessors(chain = true)
@TableName("return_order")
@Schema(description = "统一退货单主表")
public class ReturnOrder implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @TableField("return_no")
    @Schema(description = "退货单编号")
    private String returnNo;

    @TableField("return_type")
    @Schema(description = "退货类型")
    private String returnType;

    @TableField("source_order_id")
    @Schema(description = "来源订单ID")
    private Long sourceOrderId;

    @TableField("source_order_no")
    @Schema(description = "来源订单编号")
    private String sourceOrderNo;

    @TableField("party_id")
    @Schema(description = "供应商或客户ID")
    private Long partyId;

    @TableField("party_code")
    @Schema(description = "供应商或客户编码")
    private String partyCode;

    @TableField("party_name")
    @Schema(description = "供应商或客户名称")
    private String partyName;

    @TableField("warehouse_id")
    @Schema(description = "仓库ID")
    private Long warehouseId;

    @TableField("warehouse_name")
    @Schema(description = "仓库名称")
    private String warehouseName;

    @TableField("expected_execution_date")
    @Schema(description = "预计执行日期")
    private LocalDate expectedExecutionDate;

    @TableField("handling_type")
    @Schema(description = "处理类型")
    private String handlingType;

    @TableField("reason_code")
    @Schema(description = "退货原因编码")
    private String reasonCode;

    @TableField("return_reason")
    @Schema(description = "退货原因描述")
    private String returnReason;

    @TableField("total_amount")
    @Schema(description = "总金额（元）")
    private Long totalAmount;

    @TableField("status")
    @Schema(description = "状态")
    private String status;

    @TableField("status_reason")
    @Schema(description = "状态描述")
    private String statusReason;

    @TableField("created_by_id")
    @Schema(description = "创建人ID")
    private Long createdById;

    @TableField("created_by_name")
    @Schema(description = "创建人名称")
    private String createdByName;

    @TableField("submitted_at")
    @Schema(description = "提交时间")
    private LocalDateTime submittedAt;

    @TableField("approved_by_id")
    @Schema(description = "审批人ID")
    private Long approvedById;

    @TableField("approved_by_name")
    @Schema(description = "审批人名称")
    private String approvedByName;

    @TableField("approved_at")
    @Schema(description = "审批时间")
    private LocalDateTime approvedAt;

    @TableField("create_time")
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField("update_time")
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableField("deleted")
    @TableLogic
    @Schema(description = "是否删除")
    private Integer deleted;

    @TableField("remark")
    @Schema(description = "备注")
    private String remark;

    @TableField("version")
    @Version
    @Schema(description = "版本")
    private Integer version;
}
