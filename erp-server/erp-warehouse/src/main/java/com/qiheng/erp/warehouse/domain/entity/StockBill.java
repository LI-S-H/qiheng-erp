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
 * 库存流水凭证主表
 * </p>
 *
 * @author Li
 * @since 2026-07-19
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("stock_bill")
@Schema(description = "库存流水凭证主表")
public class StockBill implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "库存流水凭证ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "库存流水号")
    @TableField("bill_no")
    private String billNo;

    @Schema(description = "类型：PURCHASE_IN、SALES_OUT、PURCHASE_RETURN、SALES_RETURN、ADJUST_IN、ADJUST_OUT")
    @TableField("bill_type")
    private String billType;

    @Schema(description = "关联的作业单ID")
    @TableField("work_bill_id")
    private String workBillId;

    @Schema(description = "原业务单据ID")
    @TableField("business_source_id")
    private Long businessSourceId;

    @Schema(description = "原业务单据号")
    @TableField("business_source_no")
    private String businessSourceNo;

    @Schema(description = "录入方式：SOURCE_GENERATED、MANUAL_SUPPLEMENT、MANUAL_ADJUSTMENT")
    @TableField("entry_mode")
    private String entryMode;

    @Schema(description = "仓库ID")
    @TableField("warehouse_id")
    private Long warehouseId;

    @Schema(description = "仓库名称快照")
    @TableField("warehouse_name")
    private String warehouseName;

    @Schema(description = "确认人ID")
    @TableField("confirmed_by_id")
    private Long confirmedById;

    @Schema(description = "确认人姓名")
    @TableField("confirmed_by_name")
    private String confirmedByName;

    @Schema(description = "确认时间")
    @TableField("confirmed_at")
    private LocalDateTime confirmedAt;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "备注")
    @TableField("remark")
    private String remark;

}