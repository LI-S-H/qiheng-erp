package com.qiheng.erp.purchase.domain.supplier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 批量修改供应商状态请求 DTO
 */
@Data
@Schema(description = "批量修改供应商状态请求")
public class SupplierBatchStatusDto {

    @Schema(description = "供应商 ID 列表，去重且至少 1 个")
    @NotNull(message = "供应商ID列表不能为空")
    @Size(min = 1, message = "供应商ID列表不能为空")
    private List<String> supplierIds;

    @Schema(description = "Map of supplierId to current supplier.version for optimistic locking.")
    @NotNull(message = "版本号映射不能为空")
    @NotEmpty(message = "版本号映射不能为空")
    private Map<String, Integer> versionBySupplierId;

    @Schema(description = "目标状态：1 启用，0 停用")
    @NotNull(message = "状态不能为空")
    @DecimalMin(value = "0", message = "状态不能小于0")
    @DecimalMax(value = "1", message = "状态不能大于1")
    private Integer status;
}