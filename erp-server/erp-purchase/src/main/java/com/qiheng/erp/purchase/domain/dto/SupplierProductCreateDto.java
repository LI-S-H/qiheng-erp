package com.qiheng.erp.purchase.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;

/**
 * <p>
 * 新增供货产品请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-30
 */
@Data
@Schema(description = "新增供货产品请求")
public class SupplierProductCreateDto {

    @NotBlank(message = "供应商ID不能为空")
    @Schema(description = "供应商ID")
    private String supplierId;

    @NotBlank(message = "产品ID不能为空")
    @Schema(description = "产品ID")
    private String productId;

    @NotBlank(message = "供应商侧产品编码不能为空")
    @Size(max = 100, message = "供应商侧产品编码最长100个字符")
    @Schema(description = "供应商侧产品编码")
    private String supplierProductCode;

    @DecimalMin(value = "0", message = "最近采购单价不能小于0")
    @Schema(description = "最近采购单价，0-100 业务值；新增时可为 null，表示尚未采购过")
    private BigDecimal latestPurchasePrice;

    @NotNull(message = "最小起订量不能为空")
    @DecimalMin(value = "0", message = "最小起订量不能小于0")
    @Schema(description = "最小起订量，0-100 业务值")
    private BigDecimal minOrderQty;

    @NotNull(message = "预计交期天数不能为空")
    @Min(value = 0, message = "预计交期天数不能小于0")
    @Schema(description = "预计交期天数")
    private Integer leadTimeDays;

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

    @NotNull(message = "AI推荐分不能为空")
    @DecimalMin(value = "0", message = "AI推荐分最小为0")
    @DecimalMax(value = "100", message = "AI推荐分最大为100")
    @Schema(description = "AI推荐分，0-100 业务值")
    private BigDecimal aiScore;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态值不合法")
    @Max(value = 1, message = "状态值不合法")
    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @Size(max = 500, message = "备注最长500个字符")
    @Schema(description = "备注")
    private String remark;

    @Min(value = 0, message = "版本号不合法")
    @Schema(description = "乐观锁版本号，编辑时必传")
    private Integer version;
}
