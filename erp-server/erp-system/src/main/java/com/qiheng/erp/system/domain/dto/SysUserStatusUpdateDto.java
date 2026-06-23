package com.qiheng.erp.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "用户状态更新DTO")
public class SysUserStatusUpdateDto {

    @NotNull(message = "用户ID不能为空")
    @Schema(description = "用户ID")
    private List<String> userIds;

    @NotNull(message = "用户状态不能为空")
    @Schema(description = "用户状态")
    private Integer status;
}
