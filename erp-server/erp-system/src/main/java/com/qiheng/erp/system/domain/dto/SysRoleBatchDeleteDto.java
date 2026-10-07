package com.qiheng.erp.system.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 批量删除角色请求。
 *
 * <p>角色仍被用户绑定时，服务层会整体拒绝本次删除，避免删除时隐式解除用户角色关系。</p>
 */
@Data
@Schema(description = "批量删除角色请求")
public class SysRoleBatchDeleteDto {

    @NotEmpty(message = "角色ID列表不能为空")
    @Size(max = 100, message = "单次最多删除100条")
    @Schema(description = "角色ID列表")
    private List<String> roleIds;
}
