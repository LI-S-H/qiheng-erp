package com.qiheng.erp.warehouse.domain.warehouse.entity;

import com.baomidou.mybatisplus.annotation.*;

import java.io.Serial;
import java.time.LocalDateTime;
import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 仓库表
 * </p>
 *
 * @author Li
 * @since 2026-07-16
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("warehouse")
@Schema(name = "Warehouse对象", description = "仓库表")
public class Warehouse implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "仓库ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "仓库编码")
    @TableField("warehouse_code")
    private String warehouseCode;

    @Schema(description = "仓库名称")
    @TableField("warehouse_name")
    @NotNull(message = "仓库名称不能为空")
    private String warehouseName;

    @Schema(description = "联系人")
    @TableField("contact_name")
    @NotNull(message = "联系人不能为空")
    private String contactName;

    @Schema(description = "联系电话")
    @TableField("contact_phone")
    @NotNull(message = "联系电话不能为空")
    private String contactPhone;

    @Schema(description = "仓库地址")
    @TableField("address")
    @NotNull(message = "仓库地址不能为空")
    private String address;

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
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

    @Schema(description = "备注")
    @TableField("remark")
    private String remark;

    @Schema(description = "乐观锁版本号")
    @Version
    @TableField("version")
    private Integer version;


}