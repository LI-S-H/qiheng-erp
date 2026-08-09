package com.qiheng.erp.sales.domain.salesorder.entity;

import java.io.Serial;
import java.math.BigDecimal;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 销售订单明细表
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("sales_order_item")
@Schema(description = "销售订单明细表")
public class SalesOrderItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "明细ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "销售订单ID")
    private Long salesOrderId;

    @Schema(description = "销售单号冗余")
    private String salesNo;

    @Schema(description = "产品ID")
    private Long productId;

    @Schema(description = "产品编码冗余")
    private String productCode;

    @Schema(description = "产品名称冗余")
    private String productName;

    @Schema(description = "单位名称冗余")
    private String unitName;

    @Schema(description = "销售数量")
    private BigDecimal quantity;

    @Schema(description = "已锁定库存数量")
    private BigDecimal lockedQty;

    @Schema(description = "已出库数量")
    private BigDecimal outboundQty;

    @Schema(description = "销售单价")
    private BigDecimal unitPrice;

    @Schema(description = "明细金额")
    private BigDecimal totalAmount;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;

    @Schema(description = "备注")
    private String remark;


}
