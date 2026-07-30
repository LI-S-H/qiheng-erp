package com.qiheng.erp.warehouse.domain.outbound.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;

import java.io.Serial;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import com.qiheng.erp.warehouse.domain.support.StockBillDetailVoMapping;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 出库单明细表
 * </p>
 *
 * @author Li
 * @since 2026-07-26
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("outbound_bill_item")
@Schema(description="OutboundBillItem对象")
public class OutboundBillItem implements Serializable, StockBillDetailVoMapping.BillItemSource {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "出库单明细ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "出库单ID")
    @TableField("outbound_bill_id")
    private Long outboundBillId;

    @Schema(description = "出库单号冗余")
    @TableField("outbound_no")
    private String outboundNo;

    @Schema(description = "来源单据明细ID")
    @TableField("source_item_id")
    private Long sourceItemId;

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

    @Schema(description = "来源计划数量，按100倍整数存储")
    @TableField("plan_qty")
    private Long planQty;

    @Schema(description = "生成本单前累计已出库数量，按100倍整数存储")
    @TableField("processed_qty")
    private Long processedQty;

    @Schema(description = "本次出库数量，按100倍整数存储")
    @TableField("current_qty")
    private Long currentQty;

    @Schema(description = "确认本单后剩余未出库数量，按100倍整数存储")
    @TableField("pending_qty")
    private Long pendingQty;

    @Schema(description = "合格数量，按100倍整数存储；采购退货出库使用")
    @TableField("qualified_qty")
    private Long qualifiedQty;

    @Schema(description = "不合格数量，按100倍整数存储；采购退货出库使用")
    @TableField("defective_qty")
    private Long defectiveQty;

    @Schema(description = "确认后生成的库存流水明细ID")
    @TableField("stock_bill_item_id")
    private Long stockBillItemId;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema(description = "备注")
    @TableField("remark")
    private String remark;

    @Override
    @JsonIgnore
    public Long getBillId() {
        return outboundBillId;
    }

    @Override
    @JsonIgnore
    public String getBillNo() {
        return outboundNo;
    }


}
