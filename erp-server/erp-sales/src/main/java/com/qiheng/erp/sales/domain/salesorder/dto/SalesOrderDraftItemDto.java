package com.qiheng.erp.sales.domain.salesorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * <p>
 * 销售订单草稿明细 DTO
 * </p>
 *
 * @author Li
 * @since 2026-08-11
 */
@Data
@Schema(description = "销售订单草稿明细")
public class SalesOrderDraftItemDto {

    @Schema(description = "明细ID；编辑草稿时传已有明细 ID，新增或创建时不传")
    private String salesOrderItemId;

    @Schema(description = "产品ID，对应 `product.id`，前端按字符串传输 BIGINT")
    @NotBlank(message = "产品ID不能为空")
    private String productId;

    @Schema(description = "销售数量，业务真实值；后端按 100 倍整数持久化，小数位不得超过产品 quantityPrecision")
    @NotNull(message = "销售数量不能为空")
    @DecimalMin(value = "0.01", message = "销售数量必须大于0")
    private BigDecimal quantity;

    @Schema(description = "销售单价，业务真实值；后端按 100 倍整数持久化")
    @NotNull(message = "销售单价不能为空")
    @DecimalMin(value = "0", message = "销售单价不能小于0")
    private BigDecimal unitPrice;

    @Schema(description = "明细备注")
    @Size(max = 500, message = "明细备注长度不能超过500个字符")
    private String remark;
}