package com.qiheng.erp.sales.domain.salesorder.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * <p>
 * 销售订单草稿新增请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-08-11
 */
@Data
@Schema(description = "销售订单草稿新增请求")
public class SalesOrderCreateDto {

    @Schema(description = "客户ID，对应 `customer.id`，前端按字符串传输 BIGINT")
    @NotBlank(message = "客户ID不能为空")
    private String customerId;

    @Schema(description = "出库仓库ID，对应 `warehouse.id`，前端按字符串传输 BIGINT")
    @NotBlank(message = "出库仓库ID不能为空")
    private String warehouseId;

    @Schema(description = "预计发货日期；草稿可为空，提交和审核前必须非空")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expectedDeliveryDate;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注长度不能超过500个字符")
    private String remark;

    @NotEmpty(message = "销售明细不能为空")
    @Valid
    @Schema(description = "销售明细列表，至少 1 条")
    private List<SalesOrderDraftItemDto> items;
}