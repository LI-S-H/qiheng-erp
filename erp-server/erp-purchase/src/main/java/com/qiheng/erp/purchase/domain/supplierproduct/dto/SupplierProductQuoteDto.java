package com.qiheng.erp.purchase.domain.supplierproduct.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <p>
 * 人工维护或清空供货关系报价请求 DTO。
 * </p>
 */
@Data
@Schema(description = "供货关系报价请求")
public class SupplierProductQuoteDto {
    @Schema(description = "乐观锁版本号，更新时必传")
    @NotNull @Min(0)
    private Integer version;

    @Schema(description = "当前人工有效报价；为空表示清空报价")
    @DecimalMin(value = "0.01", message = "初始报价必须大于0")
    private BigDecimal quotedPurchasePrice;

    @Schema(description = "报价有效截止日")
    private LocalDate quoteValidUntil;

    @Schema(description = "报价原因")
    @NotBlank
    @Size(max = 500)
    private String reason;
}