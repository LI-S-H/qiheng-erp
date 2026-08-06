package com.qiheng.erp.returnorder.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * <p>
 * 创建退货单明细请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Data
@Schema(description = "创建退货单明细请求")
public class ReturnOrderItemCreateDto {

    @NotBlank(message = "来源订单明细ID不能为空")
    @Schema(description = "来源订单明细ID")
    private String sourceOrderItemId;

    @NotNull(message = "申请退回数量不能为空")
    @DecimalMin(value = "0.01", message = "申请退回数量必须大于0")
    @Schema(description = "申请退回数量，业务值")
    private BigDecimal requestedQty;

    @Size(max = 500, message = "备注最长500个字符")
    @Schema(description = "备注")
    private String remark;
}
