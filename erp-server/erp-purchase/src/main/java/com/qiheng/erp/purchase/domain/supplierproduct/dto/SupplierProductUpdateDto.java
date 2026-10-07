package com.qiheng.erp.purchase.domain.supplierproduct.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * <p>
 * 供货关系基础资料编辑请求 DTO。
 * 供应商、产品、报价和自动评分均不可通过本请求修改。
 * </p>
 */
@Data
@Schema(description = "供货关系基础资料编辑请求")
public class SupplierProductUpdateDto {
    @Schema(description = "乐观锁版本号，编辑时必传")
    @NotNull @Min(0)
    private Integer version;

    @Schema(description = "最小起订量")
    @NotNull @DecimalMin("0")
    private BigDecimal minOrderQty;

    @Schema(description = "状态：1启用，0禁用")
    @NotNull @Min(0) @Max(1)
    private Integer status;

    @Schema(description = "备注", maxLength = 500)
    @Size(max = 500)
    private String remark;
}