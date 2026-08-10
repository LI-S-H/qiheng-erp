package com.qiheng.erp.sales.domain.salesorder.entity;

import java.io.Serial;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 销售订单主表
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sales_order")
@Schema(description = "销售订单主表")
public class SalesOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "销售订单ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "销售单号")
    @TableField("sales_no")
    private String salesNo;

    @Schema(description = "客户ID")
    @TableField("customer_id")
    private Long customerId;

    @Schema(description = "客户编码冗余")
    @TableField("customer_code")
    private String customerCode;

    @Schema(description = "客户名称冗余")
    @TableField("customer_name")
    private String customerName;

    @Schema(description = "出库仓库ID")
    @TableField("warehouse_id")
    private Long warehouseId;

    @Schema(description = "出库仓库名称冗余")
    @TableField("warehouse_name")
    private String warehouseName;

    @Schema(description = "状态：DRAFT、SUBMITTED、APPROVED、PARTIAL_OUTBOUND、OUTBOUND_DONE、CANCELLED")
    @TableField("status")
    private String status;

    @Schema(description = "订单总金额，放大100倍保存，N 表示 N/100.00")
    @TableField("total_amount")
    private Integer totalAmount;

    @Schema(description = "预计发货日期")
    @TableField("expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @Schema(description = "库存锁定时间")
    @TableField("locked_at")
    private LocalDateTime lockedAt;

    @Schema(description = "创建人ID")
    @TableField("created_by_id")
    private Long createdById;

    @Schema(description = "创建人姓名")
    @TableField("created_by_name")
    private String createdByName;

    @Schema(description = "提交时间")
    @TableField("submitted_at")
    private LocalDateTime submittedAt;

    @Schema(description = "提交人ID")
    @TableField("submitted_by_id")
    private Long submittedById;

    @Schema(description = "提交人姓名")
    @TableField("submitted_by_name")
    private String submittedByName;

    @Schema(description = "审核人ID")
    @TableField("approved_by_id")
    private Long approvedById;

    @Schema(description = "审核人姓名")
    @TableField("approved_by_name")
    private String approvedByName;

    @Schema(description = "审核时间")
    @TableField("approved_at")
    private LocalDateTime approvedAt;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0正常，1删除")
    @TableField("deleted")
    @TableLogic
    private Integer deleted;

    @Schema(description = "备注")
    @TableField("remark")
    private String remark;

    @Schema(description = "乐观锁版本号")
    @TableField("version")
    private Integer version;


}
