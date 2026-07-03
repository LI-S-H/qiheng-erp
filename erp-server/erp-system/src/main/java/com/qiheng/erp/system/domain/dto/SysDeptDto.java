package com.qiheng.erp.system.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "部门列表响应")
public class SysDeptDto {

    @Schema(description = "部门ID")
    private String deptId;

    @NotNull(message = "上级部门ID不能为空")
    @Schema(description = "上级部门ID，顶级为0")
    private String parentId;

    @Schema(description = "祖级路径")
    private String ancestors;

    @NotEmpty(message = "部门名称不能为空")
    @Schema(description = "部门名称")
    private String deptName;

    @NotNull(message = "状态不能为空")
    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @Schema(description = "部门用户数量")
    private Integer userCount;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
