package com.qiheng.erp.system.domain.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "部门列表响应")
public class SysDeptDto {

    @Schema(description = "部门ID")
    private String deptId;

    @Schema(description = "上级部门ID，顶级为0")
    private String parentId;

    @Schema(description = "祖级路径")
    private String ancestors;

    @Schema(description = "部门名称")
    private String deptName;

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
