package com.qiheng.erp.purchase.domain.supplier.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 批量删除供应商请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@Data
public class SupplierBatchDeleteDto {

    @NotNull(message = "供应商ID列表不能为空")
    @Size(min = 1, message = "供应商ID列表不能为空")
    private List<String> supplierIds;

    @NotNull(message = "版本号映射不能为空")
    @NotEmpty(message = "版本号映射不能为空")
    private Map<String, Integer> versionBySupplierId;
}
