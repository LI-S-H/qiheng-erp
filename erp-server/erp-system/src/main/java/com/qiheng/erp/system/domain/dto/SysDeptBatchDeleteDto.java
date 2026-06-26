package com.qiheng.erp.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "批量删除部门请求")
public class SysDeptBatchDeleteDto {

    @NotEmpty(message = "部门ID列表不能为空")
    @Size(max = 100, message = "单次最多删除100条")
    @Schema(description = "部门ID列表")
    private List<String> deptIds;
}