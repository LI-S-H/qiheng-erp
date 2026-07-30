package com.qiheng.erp.purchase.domain.supplierproduct.entity;

import java.io.Serial;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 供应商供货产品表
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("supplier_product")
@Schema(name="SupplierProduct对象", description="供应商供货产品表")
public class SupplierProduct implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "供应商供货产品ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "供应商ID")
    @TableField("supplier_id")
    private Long supplierId;

    @Schema(description = "产品ID")
    @TableField("product_id")
    private Long productId;

    @Schema(description = "供应商侧产品编码")
    @TableField("supplier_product_code")
    private String supplierProductCode;

    @Schema(description = "最近采购单价，放大100倍保存")
    @TableField("latest_purchase_price")
    private Integer latestPurchasePrice;

    @Schema(description = "最小起订量，放大100倍保存")
    @TableField("min_order_qty")
    private Integer minOrderQty;

    @Schema(description = "预计交期天数")
    @TableField("lead_time_days")
    private Integer leadTimeDays;

    @Schema(description = "该产品维度交付评分，放大100倍保存，10000表示100.00")
    @TableField("delivery_score")
    private Integer deliveryScore;

    @Schema(description = "该产品维度质量评分，放大100倍保存，10000表示100.00")
    @TableField("quality_score")
    private Integer qualityScore;

    @Schema(description = "该产品维度价格评分，放大100倍保存，10000表示100.00")
    @TableField("price_score")
    private Integer priceScore;

    @Schema(description = "AI/规则综合推荐分，放大100倍保存，10000表示100.00")
    @TableField("ai_score")
    private Integer aiScore;

    @Schema(description = "最近采购时间")
    @TableField("last_purchase_at")
    private LocalDateTime lastPurchaseAt;

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
    @TableLogic
    private Integer deleted;

    @Schema(description = "备注")
    @TableField("remark")
    private String remark;

    @Schema(description = "乐观锁版本号")
    @TableField("version")
    @Version
    private Integer version;


}
