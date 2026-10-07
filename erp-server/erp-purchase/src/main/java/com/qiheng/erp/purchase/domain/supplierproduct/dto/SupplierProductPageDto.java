package com.qiheng.erp.purchase.domain.supplierproduct.dto;

import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.purchase.domain.supplierproduct.enums.QuoteStatus;
import com.qiheng.erp.purchase.domain.supplierproduct.enums.SupplierScoreStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * <p>
 * 供货产品分页查询请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-30
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "供货产品分页查询请求")
public class SupplierProductPageDto extends PageQuery {

    @Schema(description = "供应商ID（精确匹配）")
    private String supplierId;

    @Schema(description = "产品ID，精确匹配")
    private String productId;

    @Schema(description = "供应商名称（模糊查询）")
    private String supplierName;

    @Schema(description = "产品编码（模糊查询）")
    private String productCode;

    @Schema(description = "产品名称（模糊查询）")
    private String productName;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @Schema(description = "评分状态：NOT_READY、READY")
    @Pattern(regexp = "NOT_READY|READY", message = "评分状态不合法")
    private String scoreStatus;

    @Schema(description = "报价状态：NONE、VALID、EXPIRED")
    @Pattern(regexp = "NONE|VALID|EXPIRED", message = "报价状态不合法")
    private String quoteStatus;

    @Schema(description = "报价有效截止日上限")
    private LocalDate quoteValidUntilEnd;

    @Schema(description = "质量分下限，接口使用 0-100 业务值")
    private BigDecimal qualityScoreMin;

    @Schema(description = "质量分上限，接口使用 0-100 业务值")
    private BigDecimal qualityScoreMax;

    @Schema(description = "价格分下限，接口使用 0-100 业务值")
    private BigDecimal priceScoreMin;

    @Schema(description = "价格分上限，接口使用 0-100 业务值")
    private BigDecimal priceScoreMax;

    @Schema(description = "推荐分下限，接口使用 0-100 业务值")
    private BigDecimal aiScoreMin;

    @Schema(description = "推荐分上限，接口使用 0-100 业务值")
    private BigDecimal aiScoreMax;

    @Schema(description = "评分样本金额下限，单位元")
    private BigDecimal scoreBasisAmountMin;

    @Schema(description = "评分样本金额上限，单位元")
    private BigDecimal scoreBasisAmountMax;

    @Schema(description = "最小起订量下限")
    private BigDecimal minOrderQtyMin;

    @Schema(description = "最小起订量上限")
    private BigDecimal minOrderQtyMax;

}
