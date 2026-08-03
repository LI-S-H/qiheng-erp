package com.qiheng.erp.purchase.domain.purchaseorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * <p>
 * 采购订单明细项请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Data
@Schema(description = "采购订单明细项请求")
public class PurchaseOrderItemDto {

    @Schema(description = "明细ID，编辑时传已有明细ID表示更新，不传表示新增")
    private String purchaseOrderItemId;

    @NotBlank(message = "供应商供货产品ID不能为空")
    @Schema(description = "供应商供货产品ID")
    private String supplierProductId;

    @NotBlank(message = "产品ID不能为空")
    @Schema(description = "产品ID")
    private String productId;

    @NotNull(message = "采购数量不能为空")
    @DecimalMin(value = "0.01", message = "采购数量必须大于0")
    @Schema(description = "采购数量")
    private BigDecimal quantity;

    @NotNull(message = "采购单价不能为空")
    @DecimalMin(value = "0", message = "采购单价不能小于0")
    @Schema(description = "采购单价")
    private BigDecimal unitPrice;

    @NotNull(message = "供应商推荐分不能为空")
    @DecimalMin(value = "0", message = "供应商推荐分最小为0")
    @DecimalMax(value = "100", message = "供应商推荐分最大为100")
    @Schema(description = "下单时供应商推荐分，0-100 业务值")
    private BigDecimal selectedSupplierScore;

    @Size(max = 500, message = "备注最长500个字符")
    @Schema(description = "备注")
    private String remark;
}
