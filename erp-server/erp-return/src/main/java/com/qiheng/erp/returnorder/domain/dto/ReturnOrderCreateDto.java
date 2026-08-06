package com.qiheng.erp.returnorder.domain.dto;

import com.qiheng.erp.returnorder.domain.port.ReturnType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * <p>
 * 创建统一退货单草稿请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Data
@Schema(description = "创建统一退货单草稿请求")
public class ReturnOrderCreateDto {

    @NotNull(message = "退货类型不能为空")
    @Schema(description = "退货类型：PURCHASE_RETURN/SALES_RETURN")
    private ReturnType returnType;

    @NotBlank(message = "来源订单ID不能为空")
    @Schema(description = "来源订单ID")
    private String sourceOrderId;

    @NotBlank(message = "退货仓库ID不能为空")
    @Schema(description = "退货仓库ID")
    private String warehouseId;

    @Schema(description = "预计执行日期，草稿可空，提交前必填")
    private LocalDate expectedExecutionDate;

    @NotBlank(message = "处理方式不能为空")
    @Schema(description = "处理方式：REFUND/EXCHANGE/OTHER")
    private String handlingType;

    @NotBlank(message = "退货原因编码不能为空")
    @Schema(description = "退货原因编码")
    private String reasonCode;

    @NotBlank(message = "退货原因不能为空")
    @Size(max = 500, message = "退货原因最长500个字符")
    @Schema(description = "退货原因补充说明，OTHER时必须非空")
    private String returnReason;

    @Size(max = 500, message = "备注最长500个字符")
    @Schema(description = "备注")
    private String remark;

    @NotNull(message = "退货明细不能为空")
    @Size(min = 1, message = "退货明细不能为空")
    @Valid
    @Schema(description = "退货明细列表")
    private List<ReturnOrderItemCreateDto> items;
}
