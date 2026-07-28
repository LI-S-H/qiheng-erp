package com.qiheng.erp.warehouse.domain.vo.common;

import com.qiheng.erp.warehouse.domain.support.StockBillDetailVoMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 出入库工作单分页汇总公共字段。
 */
@Data
public class StockBillSummaryBaseVo implements StockBillDetailVoMapping.SummaryTarget {

    @Schema(description = "系统自动录单数")
    private int sourceGeneratedCount;

    @Schema(description = "待确认数")
    private int pendingCount;

    @Schema(description = "已确认数")
    private int confirmedCount;

    @Schema(description = "已取消数")
    private int cancelledCount;
}
