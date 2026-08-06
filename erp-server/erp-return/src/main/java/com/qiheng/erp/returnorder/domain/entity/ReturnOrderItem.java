package com.qiheng.erp.returnorder.domain.entity;

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
 * 统一退货单明细。
 *
 * <p>数量字段以放大 100 倍的整数存储，展示和接口转换统一由 {@code QtyUtil} 处理。</p>
 */
@Data
@Accessors(chain = true)
@TableName("return_order_item")
@Schema(description = "统一退货单明细")
public class ReturnOrderItem implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    @Schema(description = "退货单明细 ID")
    private Long id;

    @TableField("return_order_id")
    @Schema(description = "退货单 ID")
    private Long returnOrderId;

    @TableField("source_order_item_id")
    @Schema(description = "来源订单明细 ID")
    private Long sourceOrderItemId;

    @TableField("product_id")
    @Schema(description = "商品 ID")
    private Long productId;

    @TableField("product_code")
    @Schema(description = "商品编码")
    private String productCode;

    @TableField("product_name")
    @Schema(description = "商品名称")
    private String productName;

    @TableField("unit_name")
    @Schema(description = "单位名称")
    private String unitName;

    @TableField("quantity_precision")
    @Schema(description = "数量精度")
    private Integer quantityPrecision;

    @TableField("source_fulfilled_qty")
    @Schema(description = "来源订单已满量数量")
    private Integer sourceFulfilledQty;

    @TableField("requested_qty")
    @Schema(description = "请求数量")
    private Integer requestedQty;

    @TableField("approved_qty")
    @Schema(description = "已审批数量")
    private Integer approvedQty;

    @TableField("processed_qty")
    @Schema(description = "已处理数量")
    private Integer processedQty;

    @TableField("unit_price")
    @Schema(description = "单价")
    private Integer unitPrice;

    @TableField("total_amount")
    @Schema(description = "总金额")
    private Integer totalAmount;

    @TableField("create_time")
    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @TableField("update_time")
    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @TableField("remark")
    @Schema(description = "备注")
    private String remark;
}
