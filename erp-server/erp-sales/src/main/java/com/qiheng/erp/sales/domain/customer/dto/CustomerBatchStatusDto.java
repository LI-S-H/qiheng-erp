package com.qiheng.erp.sales.domain.customer.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 批量修改客户状态请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Data
public class CustomerBatchStatusDto {

    @NotNull(message = "客户ID列表不能为空")
    @Size(min = 1, message = "客户ID列表不能为空")
    private List<String> customerIds;

    @NotNull(message = "版本号映射不能为空")
    @NotEmpty(message = "版本号映射不能为空")
    private Map<String, Integer> versionByCustomerId;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态不能小于0")
    @Max(value = 1, message = "状态不能大于1")
    private Integer status;
}
