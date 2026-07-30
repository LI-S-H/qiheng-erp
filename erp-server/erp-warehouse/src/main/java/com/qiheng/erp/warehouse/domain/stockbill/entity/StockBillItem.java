package com.qiheng.erp.warehouse.domain.stockbill.entity;

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
 * 库存流水凭证明细表
 * </p>
 *
 * @author Li
 * @since 2026-07-19
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("stock_bill_item")
@Schema(description = "库存流水凭证明细表")
public class StockBillItem implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "库存流水凭证明细ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "库存流水凭证ID")
    @TableField("bill_id")
    private Long billId;

    @Schema(description = "原业务来源明细ID")
    @TableField("business_source_item_id")
    private Long businessSourceItemId;

    @Schema(description = "出入库单明细ID")
    @TableField("work_bill_item_id")
    private Long workBillItemId;

    @Schema(description = "产品ID")
    @TableField("product_id")
    private Long productId;

    @Schema(description = "产品编码快照")
    @TableField("product_code")
    private String productCode;

    @Schema(description = "产品名称快照")
    @TableField("product_name")
    private String productName;

    @Schema(description = "单位名称快照")
    @TableField("unit_name")
    private String unitName;

    @Schema(description = "数量小数位快照：0-2")
    @TableField("quantity_precision")
    private Integer quantityPrecision;

    @Schema(description = "变动前库存，按100倍整数存储")
    @TableField("before_qty")
    private Long beforeQty;

    @Schema(description = "库存变动数量，入库为正、出库为负，按100倍整数存储")
    @TableField("change_qty")
    private Long changeQty;

    @Schema(description = "变动后库存，按100倍整数存储")
    @TableField("after_qty")
    private Long afterQty;

    @Schema(description = "合格数量，按100倍整数存储")
    @TableField("qualified_qty")
    private Long qualifiedQty;

    @Schema(description = "不合格数量，按100倍整数存储")
    @TableField("defective_qty")
    private Long defectiveQty;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "备注")
    @TableField("remark")
    private String remark;


}