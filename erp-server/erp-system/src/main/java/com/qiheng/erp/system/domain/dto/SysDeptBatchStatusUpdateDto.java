package com.qiheng.erp.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "批量状态修改请求")
public class SysDeptBatchStatusUpdateDto {

    @NotEmpty(message = "部门ID列表不能为空")
    @Size(max = 100, message = "单次最多操作100条")
    @Schema(description = "部门ID列表")
    private List<Long> deptIds;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态值只能为0或1")
    @Max(value = 1, message = "状态值只能为0或1")
    @Schema(description = "状态：1启用，0禁用")
    private Integer status;
}