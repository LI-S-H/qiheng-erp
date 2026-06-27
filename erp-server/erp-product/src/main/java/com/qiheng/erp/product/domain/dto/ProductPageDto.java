package com.qiheng.erp.product.domain.dto;

import com.qiheng.erp.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "产品分页查询请求")
public class ProductPageDto extends PageQuery {

    @Schema(description = "产品编码（模糊查询）")
    private String productCode;

    @Schema(description = "产品名称（模糊查询）")
    private String productName;

    @Schema(description = "产品分类ID")
    private Long categoryId;

    @Schema(description = "品牌名称（模糊查询）")
    private String brandName;

    @Schema(description = "条码（模糊查询）")
    private String barcode;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;
}