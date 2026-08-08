package com.qiheng.erp.returnorder.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 审核统一退货单请求 DTO。
 *
 * @author Li
 * @since 2026-08-08
 */
@Data
@Schema(description = "审核统一退货单请求")
public class ReturnOrderApproveRequest {

    @NotNull(message = "版本号不能为空")
    @Min(value = 0, message = "版本号不能小于0")
    @Schema(description = "乐观锁版本号")
    private Integer version;

    @NotEmpty(message = "审核明细不能为空")
    @Valid
    @Schema(description = "审核明细列表")
    private List<ReturnOrderApproveItem> items;
}
