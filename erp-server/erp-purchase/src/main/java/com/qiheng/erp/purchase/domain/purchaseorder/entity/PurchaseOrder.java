package com.qiheng.erp.purchase.domain.purchaseorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 * 采购订单主表
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Data
@Accessors(chain = true)
@TableName("purchase_order")
@Schema(description = "采购订单主表")
public class PurchaseOrder implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "采购订单ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "采购单号")
    @TableField("purchase_no")
    private String purchaseNo;

    @Schema(description = "供应商ID")
    @TableField("supplier_id")
    private Long supplierId;

    @Schema(description = "供应商编码冗余")
    @TableField("supplier_code")
    private String supplierCode;

    @Schema(description = "供应商名称冗余")
    @TableField("supplier_name")
    private String supplierName;

    @Schema(description = "目标入库仓库ID")
    @TableField("warehouse_id")
    private Long warehouseId;

    @Schema(description = "目标入库仓库名称冗余")
    @TableField("warehouse_name")
    private String warehouseName;

    @Schema(description = "状态")
    @TableField("status")
    private String status;

    @Schema(description = "订单总金额，放大100倍保存")
    @TableField("total_amount")
    private Integer totalAmount;

    @Schema(description = "预计到货日期")
    @TableField("expected_arrival_date")
    private LocalDate expectedArrivalDate;

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
    @Version
    private Integer version;
}
