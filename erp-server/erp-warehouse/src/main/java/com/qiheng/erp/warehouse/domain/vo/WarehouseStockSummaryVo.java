package com.qiheng.erp.warehouse.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "库存汇总信息")
public class WarehouseStockSummaryVo {

    @Schema(description = "当前页去重仓库数")
    private int warehouseCount;

    @Schema(description = "当前页去重产品数")
    private int productCount;

    @Schema(description = "当前页低库存记录数")
    private int lowStockCount;

    @Schema(description = "当前页无可用库存记录数")
    private int noAvailableCount;

    @Schema(description = "当前页有锁定库存记录数")
    private int lockedCount;
}