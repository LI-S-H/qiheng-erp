package com.qiheng.erp.purchase.domain.supplierproduct.dto;

import com.qiheng.erp.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 供货产品分页查询请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-30
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "供货产品分页查询请求")
public class SupplierProductPageDto extends PageQuery {

    @Schema(description = "供应商ID（精确匹配）")
    private String supplierId;

    @Schema(description = "供应商名称（模糊查询）")
    private String supplierName;

    @Schema(description = "产品编码（模糊查询）")
    private String productCode;

    @Schema(description = "产品名称（模糊查询）")
    private String productName;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;
}
