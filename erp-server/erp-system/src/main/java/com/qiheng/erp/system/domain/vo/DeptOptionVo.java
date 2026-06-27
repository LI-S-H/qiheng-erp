package com.qiheng.erp.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "部门选项")
public class DeptOptionVo {

    @Schema(description = "部门ID")
    private String deptId;

    @Schema(description = "部门名称")
    private String deptName;

    @Schema(description = "父部门ID")
    private String parentId;

    @Schema(description = "状态")
    private Integer status;
}
