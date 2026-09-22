package com.qiheng.erp.purchase.domain.supplier.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 批量删除供应商请求 DTO
 */
@Data
@Schema(description = "批量删除供应商请求")
public class SupplierBatchDeleteDto {

    @Schema(description = "供应商 ID 列表，去重且至少 1 个")
    @NotNull(message = "供应商ID列表不能为空")
    @Size(min = 1, message = "供应商ID列表不能为空")
    private List<String> supplierIds;

    @Schema(description = "Map of supplierId to current supplier.version for optimistic locking.")
    @NotNull(message = "版本号映射不能为空")
    @NotEmpty(message = "版本号映射不能为空")
    private Map<String, Integer> versionBySupplierId;
}