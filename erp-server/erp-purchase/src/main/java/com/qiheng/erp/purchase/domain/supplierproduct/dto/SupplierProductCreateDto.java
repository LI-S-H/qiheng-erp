package com.qiheng.erp.purchase.domain.supplierproduct.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/** 创建供应商-产品供货关系。报价仅可作为建档初值。 */
@Data
@Schema(description = "创建供应商-产品供货关系")
public class SupplierProductCreateDto {
    @NotBlank
    @Schema(description = "供应商ID")
    private String supplierId;

    @NotBlank
    @Schema(description = "产品ID")
    private String productId;

    @Schema(description = "初始报价")
    @DecimalMin(value = "0.01", message = "初始报价必须大于0")
    private BigDecimal quotedPurchasePrice;

    @Schema(description = "报价有效期")
    private LocalDate quoteValidUntil;

    @Schema(description = "报价原因")
    @Size(max = 500)
    private String quoteReason;

    @Schema(description = "最小订单数量")
    @DecimalMin("0") private BigDecimal minOrderQty;
    @Schema(description = "状态")
    @NotNull @Min(0) @Max(1)
    private Integer status;

    @Schema(description = "备注")
    @Size(max = 500) private String remark;
}
