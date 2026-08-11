package com.qiheng.erp.purchase.domain.purchaseorder.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 采购订单明细表
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Data
@TableName("purchase_order_item")
public class PurchaseOrderItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @TableField("purchase_order_id")
    @Schema(description = "采购订单ID")
    private Long purchaseOrderId;

    @TableField("purchase_no")
    @Schema(description = "采购订单编号")
    private String purchaseNo;

    @TableField("supplier_product_id")
    @Schema(description = "供应商产品ID")
    private Long supplierProductId;

    @TableField("product_id")
    @Schema(description = "产品ID")
    private Long productId;

    @TableField("product_code")
    @Schema(description = "产品编号")
    private String productCode;

    @TableField("product_name")
    @Schema(description = "产品名称")
    private String productName;

    @TableField("unit_name")
    @Schema(description = "单位名称")
    private String unitName;

    @TableField("quantity")
    @Schema(description = "数量，放大100倍保存")
    private Integer quantity;

    @TableField("inbound_qty")
    @Schema(description = "已入库数量，放大100倍保存")
    private Integer inboundQty;

    @TableField("unit_price")
    @Schema(description = "单价")
    private Integer unitPrice;

    @TableField("total_amount")
    @Schema(description = "总金额")
    private Integer totalAmount;

    @TableField("selected_supplier_score")
    @Schema(description = "选中供应商评分")
    private Integer selectedSupplierScore;

    @TableField("create_time")
    @Schema(description = "创建时间")
 private LocalDateTime createTime;

    @TableField("update_time")
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableField("remark")
    @Schema(description = "备注")
    private String remark;

    @Schema(description = "乐观锁版本号")
    @Version
    private Integer version;
}
