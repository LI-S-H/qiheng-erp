package com.qiheng.erp.system.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "权限码选项分组")
public class PermissionOptionGroupVo {

    @Schema(description = "分组名称（模块编码）")
    private String group;

    @Schema(description = "该分组下的权限码选项列表")
    private List<PermissionCodeItem> codes;

    @Data
    @Schema(description = "权限码选项项")
    public static class PermissionCodeItem {

        @Schema(description = "权限码")
        private String code;

        @Schema(description = "权限码显示名称")
        private String label;
    }
}