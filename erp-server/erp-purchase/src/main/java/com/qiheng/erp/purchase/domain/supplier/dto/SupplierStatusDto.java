package com.qiheng.erp.purchase.domain.supplier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 修改单个供应商状态请求 DTO */
@Data
@Schema(description = "修改单个供应商状态请求")
public class SupplierStatusDto {

    @Schema(description = "Current supplier.version for optimistic locking.")
    @NotNull(message = "版本号不能为空")
    @DecimalMin(value = "0", message = "版本号不能小于0")
    private Integer version;

    @Schema(description = "状态：1 启用，0 停用")
    @NotNull(message = "状态不能为空")
    @DecimalMin(value = "0", message = "状态不能小于0")
    @DecimalMax(value = "1", message = "状态不能大于1")
    private Integer status;
}