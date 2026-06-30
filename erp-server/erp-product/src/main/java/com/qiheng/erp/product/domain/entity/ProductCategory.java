package com.qiheng.erp.product.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;

import java.io.Serial;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 产品分类表
 * </p>
 *
 * @author Li
 * @since 2026-06-28
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("product_category")
@Schema(description = "产品分类表")
public class ProductCategory implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "分类ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "上级分类ID，顶级为0")
    @TableField("parent_id")
    @NotNull(message = "上级分类ID不能为空")
    private Long parentId;

    @Schema(description = "分类名称")
    @TableField("category_name")
    @NotNull(message = "分类名称不能为空")
    private String categoryName;

    @Schema(description = "状态：1启用，0禁用")
    @TableField("status")
    @NotNull(message = "状态不能为空")
    private Integer status;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0正常，1删除")
    @TableField("deleted")
    private Integer deleted;

    @Schema(description = "乐观锁版本号")
    @TableField("version")
    private Integer version;


}
