package com.qiheng.erp.warehouse.domain.outbound.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.Version;

import java.io.Serial;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.annotation.TableId;
import java.io.Serializable;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import com.qiheng.erp.warehouse.domain.support.StockBillEditMapping;
import com.qiheng.erp.warehouse.domain.support.StockBillDetailVoMapping;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 出库单主表
 * </p>
 *
 * @author Li
 * @since 2026-07-26
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("outbound_bill")
@Schema(description = "出库单主表")
public class OutboundBill implements Serializable, StockBillDetailVoMapping.BillSource,
        StockBillEditMapping.EditableBill<OutboundBill> {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "出库单ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "出库单号")
    @TableField("outbound_no")
    private String outboundNo;

    @Schema(description = "类型：SALES_OUT、PURCHASE_RETURN、ADJUST_OUT")
    @TableField("outbound_type")
    private String outboundType;

    @Schema(description = "来源类型：SALES_ORDER、PURCHASE_RETURN_ORDER、STOCK_ADJUST")
    @TableField("source_type")
    private String sourceType;

    @Schema(description = "来源单据ID")
    @TableField("source_id")
    private Long sourceId;

    @Schema(description = "来源单据号")
    @TableField("source_no")
    private String sourceNo;

    @Schema(description = "来源对象ID，销售出库为客户、采购退货为供应商、调整出库为受影响仓库")
    @TableField("source_party_id")
    private Long sourcePartyId;

    @Schema(description = "来源客户、供应商或调整仓库名称快照")
    @TableField("source_party_name")
    private String sourcePartyName;

    @Schema(description = "录入方式：SOURCE_GENERATED、MANUAL_SUPPLEMENT、MANUAL_ADJUSTMENT")
    @TableField("entry_mode")
    private String entryMode;

    @Schema(description = "仓库ID")
    @TableField("warehouse_id")
    private Long warehouseId;

    @Schema(description = "仓库名称快照")
    @TableField("warehouse_name")
    private String warehouseName;

    @Schema(description = "状态：DRAFT、PENDING_CONFIRM、CONFIRMED、CANCELLED")
    @TableField("status")
    private String status;

    @Schema(description = "确认人ID")
    @TableField("confirmed_by_id")
    private Long confirmedById;

    @Schema(description = "确认人姓名")
    @TableField("confirmed_by_name")
    private String confirmedByName;

    @Schema(description = "确认时间")
    @TableField("confirmed_at")
    private LocalDateTime confirmedAt;

    @Schema(description = "创建人ID")
    @TableField("created_by_id")
    private Long createdById;

    @Schema(description = "创建人姓名")
    @TableField("created_by_name")
    private String createdByName;

    @Schema(description = "业务负责人ID")
    @TableField("responsible_by_id")
    private Long responsibleById;

    @Schema(description = "业务负责人姓名快照")
    @TableField("responsible_by_name")
    private String responsibleByName;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @TableField("deleted")
    @TableLogic
    @Schema(description = "逻辑删除：0正常，1删除")
    private Integer deleted;

    @Schema(description = "手工补录或库存调整原因")
    @TableField("manual_reason")
    private String manualReason;

    @Schema(description = "备注")
    @TableField("remark")
    private String remark;

    @Version
    @Schema(description = "乐观锁版本号")
    @TableField("version")
    private Integer version;

    @Override
    @JsonIgnore
    public String getBillNo() {
        return outboundNo;
    }

    @Override
    @JsonIgnore
    public String getBillType() {
        return outboundType;
    }


}
