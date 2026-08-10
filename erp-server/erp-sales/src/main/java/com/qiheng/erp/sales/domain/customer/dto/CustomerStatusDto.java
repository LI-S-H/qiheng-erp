package com.qiheng.erp.sales.domain.customer.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * <p>
 * 修改单个客户状态请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Data
public class CustomerStatusDto {

    @NotNull(message = "版本号不能为空")
    @Min(value = 0, message = "版本号不能小于0")
    private Integer version;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态不能小于0")
    @Max(value = 1, message = "状态不能大于1")
    private Integer status;
}
