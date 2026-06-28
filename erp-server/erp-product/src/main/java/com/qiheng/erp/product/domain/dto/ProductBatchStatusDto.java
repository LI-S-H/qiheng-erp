package com.qiheng.erp.product.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class ProductBatchStatusDto {
    @NotNull(message = "产品ID列表不能为空")
    @Size(min = 1, message = "产品ID列表不能为空")
    private List<String> productIds;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态不能小于0")
    @Max(value = 1, message = "状态不能大于1")
    private Integer status;
}
