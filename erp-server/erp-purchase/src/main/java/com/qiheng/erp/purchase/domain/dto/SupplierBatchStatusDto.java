package com.qiheng.erp.purchase.domain.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 批量修改供应商状态请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@Data
public class SupplierBatchStatusDto {

    @NotNull(message = "供应商ID列表不能为空")
    @Size(min = 1, message = "供应商ID列表不能为空")
    private List<String> supplierIds;

    @NotNull(message = "版本号映射不能为空")
    @NotEmpty(message = "版本号映射不能为空")
    private Map<String, Integer> versionBySupplierId;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态不能小于0")
    @Max(value = 1, message = "状态不能大于1")
    private Integer status;
}
