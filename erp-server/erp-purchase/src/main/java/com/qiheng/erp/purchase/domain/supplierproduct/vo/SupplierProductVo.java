package com.qiheng.erp.purchase.domain.supplierproduct.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.qiheng.erp.common.config.MoneyStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 * 供货关系响应 VO；所有系统评分均可为空。
 * </p>
 */
@Data
@Schema(description = "供货关系响应")
public class SupplierProductVo {

    @Schema(description = "供应商供货产品ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long supplierProductId;

    @Schema(description = "供应商ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long supplierId;

    @Schema(description = "供应商编码")
    private String supplierCode;

    @Schema(description = "供应商名称")
    private String supplierName;

    @Schema(description = "产品ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "单位名称")
    private String unitName;

    @Schema(description = "产品数量小数位，来自 product 表")
    private Integer quantityPrecision;

    @Schema(description = "当前人工有效报价")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal quotedPurchasePrice;

    @Schema(description = "当前人工有效报价原因；无报价时为空字符串")
    private String quotedPriceReason;

    @Schema(description = "最近报价维护时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime quotedPriceUpdatedAt;

    @Schema(description = "报价有效截止日")
    private LocalDate quoteValidUntil;

    @Schema(description = "最近采购单价")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal latestPurchasePrice;

    @Schema(description = "最小起订量")
    private BigDecimal minOrderQty;

    @Schema(description = "质量评分，0-100 业务值")
    private BigDecimal qualityScore;

    @Schema(description = "价格评分，0-100 业务值")
    private BigDecimal priceScore;

    @Schema(description = "AI 推荐分，0-100 业务值")
    private BigDecimal aiScore;

    /**
     * 推荐分(产品维度),由评分系统写入。
     */
    @Schema(description = "推荐分,评分系统写入,0-100 业务值")
    private BigDecimal recommendScore;

    @Schema(description = "最近采购时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastPurchaseAt;

    @Schema(description = "最近180天完全入库采购单的金额加权平均到货周期（天），仅供分析")
    private BigDecimal avgDeliveryDays;

    @Schema(description = "最近180天评分样本金额")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal scoreBasisAmount;

    @Schema(description = "评分状态：NOT_READY、READY")
    private String scoreStatus;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @Schema(description = "乐观锁版本号")
    private Integer version;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    @Schema(description = "最后维护人ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedById;

    @Schema(description = "最后维护人姓名")
    private String updatedByName;
}