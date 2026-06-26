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
@Schema(description = "批量修改权限码状态请求")
public class SysPermissionBatchStatusDto {

    @NotEmpty(message = "权限码ID列表不能为空")
    @Size(min = 1, message = "至少选择1条")
    @Schema(description = "权限码ID列表")
    private List<String> permissionIds;

    @NotNull(message = "状态不能为空")
    @Min(value = 0, message = "状态值只能为0或1")
    @Max(value = 1, message = "状态值只能为0或1")
    @Schema(description = "状态：1启用，0禁用")
    private Integer status;
}