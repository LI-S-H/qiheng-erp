package com.qiheng.erp.product.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CategoryBatchUpdateDto {

    @NotNull(message = "分类ID列表不能为空")
    private List<String> categoryIds;

    @NotNull(message = "状态不能为空")
    private Integer status;
}
