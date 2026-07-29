package com.qiheng.erp.purchase.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * <p>
 * 新增供应商请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@Data
@Schema(description = "新增供应商请求")
public class SupplierCreateDto {

    @NotBlank(message = "供应商名称不能为空")
    @Size(max = 200, message = "供应商名称最长200个字符")
    @Schema(description = "供应商名称")
    private String supplierName;

    @NotBlank(message = "联系人不能为空")
    @Size(max = 100, message = "联系人最长100个字符")
    @Schema(description = "联系人")
    private String contactName;

    @NotBlank(message = "联系电话不能为空")
    @Size(max = 32, message = "联系电话最长32个字符")
    @Schema(description = "联系电话")
    private String contactPhone;

    @NotBlank(message = "地址不能为空")
    @Size(max = 255, message = "地址最长255个字符")
    @Schema(description = "地址")
    private String address;

    @NotBlank(message = "付款条件不能为空")
    @Size(max = 100, message = "付款条件最长100个字符")
    @Schema(description = "付款条件")
    private String paymentTerms;

    @NotNull(message = "综合评分不能为空")
    @DecimalMin(value = "0", message = "综合评分最小为0")
    @DecimalMax(value = "100", message = "综合评分最大为100")
    @Schema(description = "综合评分，0-100 业务值")
    private BigDecimal overallScore;

    @NotNull(message = "交付评分不能为空")
    @DecimalMin(value = "0", message = "交付评分最小为0")
    @DecimalMax(value = "100", message = "交付评分最大为100")
    @Schema(description = "交付评分，0-100 业务值")
    private BigDecimal deliveryScore;

    @NotNull(message = "质量评分不能为空")
    @DecimalMin(value = "0", message = "质量评分最小为0")
    @DecimalMax(value = "100", message = "质量评分最大为100")
    @Schema(description = "质量评分，0-100 业务值")
    private BigDecimal qualityScore;

    @NotNull(message = "价格评分不能为空")
    @DecimalMin(value = "0", message = "价格评分最小为0")
    @DecimalMax(value = "100", message = "价格评分最大为100")
    @Schema(description = "价格评分，0-100 业务值")
    private BigDecimal priceScore;

    @NotNull(message = "服务评分不能为空")
    @DecimalMin(value = "0", message = "服务评分最小为0")
    @DecimalMax(value = "100", message = "服务评分最大为100")
    @Schema(description = "服务评分，0-100 业务值")
    private BigDecimal serviceScore;

    @NotNull(message = "平均交付天数不能为空")
    @DecimalMin(value = "0", message = "平均交付天数最小为0")
    @Schema(description = "平均交付天数")
    private BigDecimal avgDeliveryDays;

    @NotNull(message = "准时交付率不能为空")
    @DecimalMin(value = "0", message = "准时交付率最小为0")
    @DecimalMax(value = "100", message = "准时交付率最大为100")
    @Schema(description = "准时交付率，0-100 业务值")
    private BigDecimal onTimeRate;

    @NotNull(message = "到货合格率不能为空")
    @DecimalMin(value = "0", message = "到货合格率最小为0")
    @DecimalMax(value = "100", message = "到货合格率最大为100")
    @Schema(description = "到货合格率，0-100 业务值")
    private BigDecimal qualifiedRate;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态值不合法")
    @Max(value = 1, message = "状态值不合法")
    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @Size(max = 500, message = "备注最长500个字符")
    @Schema(description = "备注")
    private String remark;
}
