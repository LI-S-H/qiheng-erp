package com.qiheng.erp.purchase.domain.dto;

import com.qiheng.erp.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 供应商分页查询请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "供应商分页查询请求")
public class SupplierPageDto extends PageQuery {

    @Schema(description = "供应商编码（模糊查询）")
    private String supplierCode;

    @Schema(description = "供应商名称（模糊查询）")
    private String supplierName;

    @Schema(description = "联系人（模糊查询）")
    private String contactName;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;
}
