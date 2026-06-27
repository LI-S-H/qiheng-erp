 package com.qiheng.erp.product.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Schema(description = "产品分页查询响应")
public class ProductVo {

    @Schema(description = "产品ID")
    private Long productId;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "产品分类ID")
    private Long categoryId;

    @Schema(description = "产品分类名称")
    private String categoryName;

    @Schema(description = "品牌名称")
    private String brandName;

    @Schema(description = "单位名称")
    private String unitName;

    @Schema(description = "数量小数位")
    private Integer quantityPrecision;

    @Schema(description = "规格型号")
    private String specification;

    @Schema(description = "条码")
    private String barcode;

    @Schema(description = "参考采购价")
    private BigDecimal referencePurchasePrice;

    @Schema(description = "参考销售价")
    private BigDecimal referenceSalePrice;

    @Schema(description = "安全库存数量，业务真实值")
    private BigDecimal safetyStockQty;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}