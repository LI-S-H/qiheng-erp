package com.qiheng.erp.warehouse.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "入库单分页汇总信息")
public class InboundBillSummaryVo {

    @Schema(description = "系统自动录单数")
    private int sourceGeneratedCount;

    @Schema(description = "待确认数")
    private int pendingCount;

    @Schema(description = "已确认数")
    private int confirmedCount;

    @Schema(description = "已取消数")
    private int cancelledCount;
}