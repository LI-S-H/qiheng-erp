package com.qiheng.erp.purchase.domain.purchaseorder.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * <p>
 * 新增采购订单草稿请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Data
@Schema(description = "新增采购订单草稿请求")
public class PurchaseOrderCreateDto {

    @NotBlank(message = "供应商ID不能为空")
    @Schema(description = "供应商ID")
    private String supplierId;

    @NotBlank(message = "入库仓库ID不能为空")
    @Schema(description = "入库仓库ID")
    private String warehouseId;

    @Schema(description = "预计到货日期，草稿可为空")
    private LocalDate expectedArrivalDate;

    @Size(max = 500, message = "备注最长500个字符")
    @Schema(description = "备注")
    private String remark;

    @NotEmpty(message = "采购明细不能为空")
    @Valid
    @Schema(description = "采购明细列表")
    private List<PurchaseOrderItemDto> items;
}
