package com.qiheng.erp.purchase.domain.supplierproduct.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 供货产品分页查询响应 VO
 * </p>
 *
 * @author Li
 * @since 2026-07-30
 */
@Data
@Schema(description = "供货产品分页查询响应")
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

    @Schema(description = "供应商侧产品编码")
    private String supplierProductCode;

    @Schema(description = "最近采购单价")
    private BigDecimal latestPurchasePrice;

    @Schema(description = "最小起订量")
    private BigDecimal minOrderQty;

    @Schema(description = "预计交期天数")
    private Integer leadTimeDays;

    @Schema(description = "交付评分，0-100 业务值")
    private BigDecimal deliveryScore;

    @Schema(description = "质量评分，0-100 业务值")
    private BigDecimal qualityScore;

    @Schema(description = "价格评分，0-100 业务值")
    private BigDecimal priceScore;

    @Schema(description = "AI推荐分，0-100 业务值")
    private BigDecimal aiScore;

    @Schema(description = "最近采购时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastPurchaseAt;

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
