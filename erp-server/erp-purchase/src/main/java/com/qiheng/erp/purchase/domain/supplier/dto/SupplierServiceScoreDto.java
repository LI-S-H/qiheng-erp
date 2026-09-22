package com.qiheng.erp.purchase.domain.supplier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** 人工调整服务分。服务分可清空，但每次操作都必须说明原因。 */
@Data
@Schema(description = "调整供应商服务分请求")
public class SupplierServiceScoreDto {

    @Schema(description = "当前供应商版本号，用于乐观锁校验")
    @NotNull
    @DecimalMin("0")
    private Integer version;

    @Schema(description = "新服务分；传 null 清空人工服务分，最多两位小数")
    @DecimalMin("0")
    @DecimalMax("100")
    private BigDecimal serviceScore;

    @Schema(description = "人工调整原因")
    @NotBlank
    @Size(max = 500)
    private String reason;
}