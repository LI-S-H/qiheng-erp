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
    private String productName;

    @Schema(description = "产品分类ID")
    @TableField("category_id")
    private Long categoryId;

    @Schema(description = "品牌名称")
    @TableField("brand_name")
    private String brandName;

    @Schema(description = "单位名称")
    @TableField("unit_name")
    private String unitName;

    @Schema(description = "数量小数位：0-2，离散单位通常为0")
    @TableField("quantity_precision")
    private Integer quantityPrecision;

    @Schema(description = "规格型号")
    @TableField("specification")
    private String specification;

    @Schema(description = "条码")
    @TableField("barcode")
    private String barcode;

    @Schema(description = "参考采购价")
    @TableField("reference_purchase_price")
    private BigDecimal referencePurchasePrice;

    @Schema(description = "参考销售价")
    @TableField("reference_sale_price")
    private BigDecimal referenceSalePrice;

    @Schema(description = "安全库存数量，按100倍整数存储，例如12.50存1250")
    @TableField("safety_stock_qty")
    private Long safetyStockQty;

    @Schema(description = "状态：1启用，0禁用")
    @TableField("status")
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

    @Schema(description = "乐观锁版本号")
    @TableField("version")
    private Integer version;


}
