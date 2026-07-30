package com.qiheng.erp.purchase.domain.supplierproduct.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 批量修改供货产品状态请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-30
 */
@Data
@Schema(description = "批量修改供货产品状态请求")
public class SupplierProductBatchStatusDto {

    @NotEmpty(message = "供货产品ID列表不能为空")
    @Schema(description = "供货产品ID列表")
    private List<String> supplierProductIds;

    @NotNull(message = "版本号映射不能为空")
    @Schema(description = "供货产品ID到版本号的映射，用于乐观锁")
    private Map<String, Integer> versionBySupplierProductId;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态值不合法")
    @Max(value = 1, message = "状态值不合法")
    @Schema(description = "目标状态：1启用，0停用")
    private Integer status;
}
