package com.qiheng.erp.purchase.domain.purchaseorder.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

/** 采购订单详情展示的只读流程节点。 */
@Data
@Accessors(chain = true)
@Schema(description = "采购订单流程记录节点")
public class PurchaseOrderTimelineItemVo {

    @Schema(description = "事件编码")
    private String event;

    @Schema(description = "事件发生时间")
    private LocalDateTime occurredAt;

    @Schema(description = "操作人名称")
    private String operatorName;

    @JsonSerialize(using = ToStringSerializer.class)
    @Schema(description = "关联入库单 ID")
    private Long inboundBillId;

    @Schema(description = "关联入库单号")
    private String inboundBillNo;
}
