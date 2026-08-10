package com.qiheng.erp.sales.domain.customer.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 批量删除客户请求 DTO
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Data
public class CustomerBatchDeleteDto {

    @NotNull(message = "客户ID列表不能为空")
    @Size(min = 1, message = "客户ID列表不能为空")
    private List<String> customerIds;

    @NotNull(message = "版本号映射不能为空")
    @NotEmpty(message = "版本号映射不能为空")
    private Map<String, Integer> versionByCustomerId;
}