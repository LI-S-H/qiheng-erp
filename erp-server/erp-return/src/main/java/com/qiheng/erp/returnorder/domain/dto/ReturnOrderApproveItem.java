package com.qiheng.erp.returnorder.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 审核统一退货单明细请求 DTO。
 *
 * @author Li
 * @since 2026-08-08
 */
@Data
@Schema(description = "审核统一退货单明细请求")
public class ReturnOrderApproveItem {

    @NotBlank(message = "退货明细ID不能为空")
    @Schema(description = "退货明细ID")
    private String returnOrderItemId;

    @NotNull(message = "审核数量不能为空")
    @Min(value = 0, message = "审核数量不能小于0")
    @Schema(description = "审核通过数量，不得超过对应申请数量")
    private BigDecimal approvedQty;
}
