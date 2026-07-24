package com.qiheng.erp.warehouse.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "入库单列表项")
public class InboundBillListItemVo {

    @Schema(description = "入库单ID，对应 inbound_bill.id")
    private String workBillId;

    @Schema(description = "入库单号，对应 inbound_bill.inbound_no")
    private String billNo;

    @Schema(description = "来源类型")
    private String sourceType;

    @Schema(description = "来源单据ID")
    private String sourceId;

    @Schema(description = "来源单据号")
    private String sourceNo;

    @Schema(description = "来源对象ID")
    private String sourcePartyId;

    @Schema(description = "来源对象名称")
    private String sourcePartyName;

    @Schema(description = "录入方式")
    private String entryMode;

    @Schema(description = "仓库ID")
    private String warehouseId;

    @Schema(description = "仓库名称")
    private String warehouseName;

    @Schema(description = "入库单状态")
    private String status;

    @Schema(description = "明细条数")
    private int itemCount;

    @Schema(description = "数量汇总描述")
    private String quantitySummary;

    @Schema(description = "本次入库总数量（数值）")
    private int totalCurrentQty;

    @Schema(description = "主单位名称")
    private String quantityUnitName;

    @Schema(description = "确认人ID")
    private String confirmedById;

    @Schema(description = "确认人姓名")
    private String confirmedByName;

    @Schema(description = "确认时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime confirmedAt;

    @Schema(description = "创建人ID")
    private String createdById;

    @Schema(description = "创建人姓名")
    private String createdByName;

    @Schema(description = "业务负责人ID")
    private String responsibleById;

    @Schema(description = "业务负责人姓名")
    private String responsibleByName;

    @Schema(description = "乐观锁版本号")
    private int version;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    @Schema(description = "入库类型")
    private String billType;
}