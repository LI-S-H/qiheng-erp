package com.qiheng.erp.product.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "产品分类列表响应")
public class ProductCategoryVo {

    @Schema(description = "分类ID")
    private String categoryId;

    @Schema(description = "上级分类ID，顶级为0")
    private String parentId;

    @Schema(description = "分类名称")
    private String categoryName;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @Schema(description = "该分类下产品数量")
    private Integer productCount;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}