package com.qiheng.erp.product.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 产品新增与更新请求。
 * <p>
 * 对应接口文档的 {@code ProductFormRequest}。数量与金额均以业务真实值传入，
 * 由服务层按存储规则转换：{@code safetyStockQty} 转 100 倍整数，参考价直接落 DECIMAL 列。
 *
 * @author Li
 * @since 2026-09-06
 */
@Data
@Schema(description = "产品新增与更新请求")
public class ProductSaveDto {

    @Schema(description = "产品名称")
    @NotBlank(message = "产品名称不能为空")
    @Size(max = 200, message = "产品名称长度不能超过 200")
    private String productName;

    @Schema(description = "产品分类ID；BIGINT 按字符串传输，允许为空表示不指定分类")
    private String categoryId;

    @Schema(description = "品牌名称")
    @NotNull(message = "品牌名称不能为空")
    @Size(max = 100, message = "品牌名称长度不能超过 100")
    private String brandName;

    @Schema(description = "单位名称")
    @NotBlank(message = "单位名称不能为空")
    @Size(max = 32, message = "单位名称长度不能超过 32")
    private String unitName;

    @Schema(description = "数量小数位：0-2，离散单位通常为0")
    @NotNull(message = "数量小数位不能为空")
    @Min(value = 0, message = "数量小数位不能小于 0")
    @Max(value = 2, message = "数量小数位不能大于 2")
    private Integer quantityPrecision;

    @Schema(description = "规格型号")
    @NotNull(message = "规格型号不能为空")
    @Size(max = 255, message = "规格型号长度不能超过 255")
    private String specification;

    @Schema(description = "条码，允许为空")
    @Size(max = 64, message = "条码长度不能超过 64")
    private String barcode;

    @Schema(description = "参考采购价，最多两位小数")
    @NotNull(message = "参考采购价不能为空")
    @DecimalMin(value = "0", message = "参考采购价不能为负数")
    @Digits(integer = 16, fraction = 2, message = "参考采购价最多保留两位小数")
    private BigDecimal referencePurchasePrice;

    @Schema(description = "参考销售价，最多两位小数")
    @NotNull(message = "参考销售价不能为空")
    @DecimalMin(value = "0", message = "参考销售价不能为负数")
    @Digits(integer = 16, fraction = 2, message = "参考销售价最多保留两位小数")
    private BigDecimal referenceSalePrice;

    @Schema(description = "安全库存业务真实值；小数位不得超过 quantityPrecision")
    @NotNull(message = "安全库存数量不能为空")
    @DecimalMin(value = "0", message = "安全库存数量不能为负数")
    @Digits(integer = 16, fraction = 2, message = "安全库存数量最多保留两位小数")
    private BigDecimal safetyStockQty;

    @Schema(description = "状态：1启用，0禁用")
    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态只能为 0 或 1")
    @Max(value = 1, message = "状态只能为 0 或 1")
    private Integer status;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过 500")
    private String remark;
}
