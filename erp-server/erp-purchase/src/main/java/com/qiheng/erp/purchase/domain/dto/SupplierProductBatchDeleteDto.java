package com.qiheng.erp.purchase.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 批量删除供货产品请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-30
 */
@Data
@Schema(description = "批量删除供货产品请求")
public class SupplierProductBatchDeleteDto {

    @NotEmpty(message = "供货产品ID列表不能为空")
    @Schema(description = "供货产品ID列表")
    private List<String> supplierProductIds;

    @NotNull(message = "版本号映射不能为空")
    @Schema(description = "供货产品ID -> 版本号映射")
    private Map<String, Integer> versionBySupplierProductId;
}
