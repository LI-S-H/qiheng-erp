package com.qiheng.erp.purchase.domain.supplier.dto;

import com.qiheng.erp.common.dto.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;

/**
 * <p>
 * 供应商分页查询请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "供应商分页查询请求")
public class SupplierPageDto extends PageQuery {

    @Schema(description = "供应商编码（模糊查询）")
    private String supplierCode;

    @Schema(description = "供应商名称（模糊查询）")
    private String supplierName;

    @Schema(description = "联系人（模糊查询）")
    private String contactName;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @Schema(description = "评分状态：NOT_READY、READY")
    private String scoreStatus;

    @Schema(description = "综合分下限，接口使用 0-100 业务值")
    private BigDecimal overallScoreMin;

    @Schema(description = "综合分上限，接口使用 0-100 业务值")
    private BigDecimal overallScoreMax;

    @Schema(description = "服务分下限，接口使用 0-100 业务值")
    private BigDecimal serviceScoreMin;

    @Schema(description = "服务分上限，接口使用 0-100 业务值")
    private BigDecimal serviceScoreMax;

    @Schema(description = "评分样本金额下限，单位元")
    private BigDecimal scoreBasisAmountMin;

    @Schema(description = "评分样本金额上限，单位元")
    private BigDecimal scoreBasisAmountMax;

    @Schema(description = "平均到货周期下限，单位天")
    private BigDecimal avgDeliveryDaysMin;

    @Schema(description = "平均到货周期上限，单位天")
    private BigDecimal avgDeliveryDaysMax;

}
