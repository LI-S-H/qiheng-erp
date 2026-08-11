package com.qiheng.erp.sales.domain.salesorder.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/**
 * <p>
 * 销售订单编辑请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-08-12
 */
@Data
@Schema(description = "销售订单编辑请求")
public class SalesOrderUpdateDto {

    @Schema(description = "乐观锁版本号")
    @NotNull(message = "版本号不能为空")
    @Min(value = 0, message = "版本号不能为负")
    private Integer version;

    @Schema(description = "客户ID")
    @NotBlank(message = "客户ID不能为空")
    private String customerId;

    @Schema(description = "出库仓库ID")
    @NotBlank(message = "出库仓库ID不能为空")
    private String warehouseId;

    @Schema(description = "预计发货日期")
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