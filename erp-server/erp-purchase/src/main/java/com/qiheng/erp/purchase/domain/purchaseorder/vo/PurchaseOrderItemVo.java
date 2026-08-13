package com.qiheng.erp.purchase.domain.purchaseorder.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.qiheng.erp.common.config.MoneyStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * <p>
 * 采购订单明细 VO
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Data
@Schema(description = "采购订单明细项")
public class PurchaseOrderItemVo {

    @Schema(description = "明细ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long purchaseOrderItemId;

    @Schema(description = "采购订单ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long purchaseOrderId;

    @Schema(description = "采购单号")
    private String purchaseNo;

    @Schema(description = "供应商供货产品ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long supplierProductId;

    @Schema(description = "产品ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "单位名称")
    private String unitName;

    @Schema(description = "产品数量小数位，来自 product 表")
    private Integer quantityPrecision;

    @Schema(description = "采购数量")
    private BigDecimal quantity;

    @Schema(description = "已入库数量")
    private BigDecimal inboundQty;

    @Schema(description = "采购单价")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal unitPrice;

    @Schema(description = "明细金额")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal totalAmount;

    @Schema(description = "下单时推荐分，0-100 业务值")
    private BigDecimal selectedSupplierScore;

    @Schema(description = "备注")
    private String remark;
}
