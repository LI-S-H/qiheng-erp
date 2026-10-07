package com.qiheng.erp.purchase.domain.supplierproduct.entity;

import java.io.Serial;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
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

    @Schema(description = "最近采购单价，放大100倍保存")
    @TableField("latest_purchase_price")
    private Long latestPurchasePrice;

    @Schema(description = "当前人工有效报价，单位分")
    @TableField("quoted_purchase_price")
    private Long quotedPurchasePrice;

    @Schema(description = "当前人工有效报价原因；无报价时为空字符串")
    @TableField("quoted_price_reason")
    private String quotedPriceReason;

    @Schema(description = "最近报价维护时间")
    @TableField("quoted_price_updated_at")
    private LocalDateTime quotedPriceUpdatedAt;

    @Schema(description = "报价有效截止日")
    @TableField("quote_valid_until")
    private LocalDate quoteValidUntil;

    @Schema(description = "最小起订量，放大100倍保存")
    @TableField("min_order_qty")
    private Long minOrderQty;

    @Schema(description = "该产品维度质量评分，放大100倍保存，10000表示100.00")
    @TableField("quality_score")
    private Integer qualityScore;

    @Schema(description = "该产品维度价格评分，放大100倍保存，10000表示100.00")
    @TableField("price_score")
    private Integer priceScore;

    @Schema(description = "AI/规则综合推荐分，放大100倍保存，10000表示100.00")
    // 旧接口字段仅保留兼容；数据库已统一为 recommend_score，不能再读写旧列。
    @TableField(exist = false)
    private Integer aiScore;

    /**
     * 推荐分(产品维度)。由评分重算系统写入，旧接口 aiScore 映射到此字段。
     * 评分公式:价格分×30% + 供应商交付分×30% + 产品质量分×30% + 服务分×10%。
     */
    @Schema(description = "推荐分;评分重算系统写入,放大100倍保存,10000表示100.00")
    @TableField("recommend_score")
    private Integer recommendScore;

    @Schema(description = "最近采购时间")
    @TableField("last_purchase_at")
    private LocalDateTime lastPurchaseAt;

    @Schema(description = "最近180天完全入库采购单的金额加权平均到货周期（天），仅供分析")
    @TableField("avg_delivery_days")
    private BigDecimal avgDeliveryDays;

    @Schema(description = "已确认入库累计金额，单位分；不参与质量分计算")
    @TableField("score_basis_amount")
    private Long scoreBasisAmount;

    @Schema(description = "评分状态：NOT_READY、READY")
    @TableField("score_status")
    private String scoreStatus;

    @Schema(description = "状态：1启用，0禁用")
    @TableField("status")
    private Integer status;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema(description = "最后维护人ID")
    @TableField("updated_by_id")
    private Long updatedById;

    @Schema(description = "最后维护人姓名")
    @TableField("updated_by_name")
    private String updatedByName;

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
