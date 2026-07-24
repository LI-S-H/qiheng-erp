package com.qiheng.erp.warehouse.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qiheng.erp.warehouse.domain.enums.InboundBillStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "入库单列表项")
public class InboundBillListItemVo {

    @Schema(description = "入库单ID，对应 inbound_bill.id")
    private Long inboundBillId;

    @Schema(description = "入库单号，对应 inbound_bill.inbound_no")
    private String inboundNo;

    @Schema(description = "入库单状态")
    private InboundBillStatus status;

    @Schema(description = "来源类型")
    private String sourceType;

    @Schema(description = "来源单据号")
    private String sourceNo;

    @Schema(description = "来源对象名称")
    private String sourcePartyName;

    @Schema(description = "仓库 ID")
    private Long warehouseId;

    @Schema(description = "仓库名称")
    private String warehouseName;

    @Schema(description = "明细条数")
    private Integer itemCount;

    @Schema(description = "确认人 ID")
    private Long confirmedById;

    @Schema(description = "确认人姓名")
    private String confirmedByName;

    @Schema(description = "确认时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime confirmedAt;

    @Schema(description = "创建人 ID")
    private Long createdById;

    @Schema(description = "创建人姓名")
    private String createdByName;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}