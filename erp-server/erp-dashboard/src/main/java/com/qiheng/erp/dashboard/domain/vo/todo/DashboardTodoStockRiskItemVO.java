package com.qiheng.erp.dashboard.domain.vo.todo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/** 工作台库存风险 SKU 详情项。 */
@Data
@Schema(description = "工作台库存风险 SKU 详情项")
public class DashboardTodoStockRiskItemVO {

    private String id;
    private String productCode;
    private String productName;
    private String warehouseName;
    private String unitName;
    private BigDecimal availableQty;
    private BigDecimal safetyStockQty;
    private BigDecimal suggestedPurchaseQty;
    @Schema(description = "库存健康状态", allowableValues = {"OUT_OF_STOCK", "NO_AVAILABLE", "LOW_STOCK"})
    private String severity;
}