package com.qiheng.erp.product.domain.entity;

import java.io.Serial;
import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * <p>
 * 产品表
 * </p>
 *
 * @author Li
 * @since 2026-06-28
 */
@Data
@Schema(description = "产品表")
@Accessors(chain = true)
@TableName("product")
public class Product implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "产品ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "产品编码，由后端生成且创建后不可修改")
    @TableField("product_code")
    private String productCode;

    @Schema(description = "产品名称")
    @TableField("product_name")
    @NotNull(message = "产品名称不能为空")
    private String productName;

    @Schema(description = "产品分类ID")
    @TableField("category_id")
    @NotNull(message = "产品分类ID不能为空")
    private Long categoryId;

    @Schema(description = "品牌名称")
    @TableField("brand_name")
    @NotNull(message = "品牌名称不能为空")
    private String brandName;

    @Schema(description = "单位名称")
    @TableField("unit_name")
    @NotNull(message = "单位名称不能为空")
    private String unitName;

    @Schema(description = "数量小数位：0-2，离散单位通常为0")
    @TableField("quantity_precision")
    @NotNull(message = "数量小数位不能为空")
    private Integer quantityPrecision;

    @Schema(description = "规格型号")
    @TableField("specification")
    @NotNull(message = "规格型号不能为空")
    private String specification;

    @Schema(description = "条码")
    @TableField("barcode")
    @NotNull(message = "条码不能为空")
    private String barcode;

    @Schema(description = "参考采购价")
    @TableField("reference_purchase_price")
    @NotNull(message = "参考采购价不能为空")
    private BigDecimal referencePurchasePrice;

    @Schema(description = "参考销售价")
    @TableField("reference_sale_price")
    @NotNull(message = "参考销售价不能为空")
    private BigDecimal referenceSalePrice;

    @Schema(description = "安全库存数量，按100倍整数存储，例如12.50存1250")
    @TableField("safety_stock_qty")
    @NotNull(message = "安全库存数量不能为空")
    private Long safetyStockQty;

    @Schema(description = "状态：1启用，0禁用")
    @TableField("status")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0正常，1删除")
    @TableField("deleted")
    private Integer deleted;

    @Schema(description = "备注")
    @TableField("remark")
    private String remark;
}