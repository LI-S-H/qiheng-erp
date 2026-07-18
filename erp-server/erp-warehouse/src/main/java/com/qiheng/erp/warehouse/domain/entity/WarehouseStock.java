package com.qiheng.erp.warehouse.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;

import java.io.Serial;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 库存余额表
 * </p>
 *
 * @author Li
 * @since 2026-07-17
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("warehouse_stock")
@Schema(description = "库存余额表")
public class WarehouseStock implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "库存ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "仓库ID")
    @TableField("warehouse_id")
    private Long warehouseId;

    @Schema(description = "仓库编码冗余")
    @TableField("warehouse_code")
    private String warehouseCode;

    @Schema(description = "仓库名称冗余")
    @TableField("warehouse_name")
    private String warehouseName;

    @Schema(description = "产品ID")
    @TableField("product_id")
    private Long productId;

    @Schema(description = "产品编码冗余")
    @TableField("product_code")
    private String productCode;

    @Schema(description = "产品名称冗余")
    @TableField("product_name")
    private String productName;

    @Schema(description = "单位名称冗余")
    @TableField("unit_name")
    private String unitName;

    @Schema(description = "当前库存数量，按100倍整数存储")
    @TableField("stock_qty")
    private Long stockQty;

    @Schema(description = "锁定库存数量，按100倍整数存储")
    @TableField("locked_qty")
    private Long lockedQty;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema(description = "乐观锁版本号")
    @TableField("version")
    private Integer version;


}
