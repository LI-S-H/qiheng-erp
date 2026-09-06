package com.qiheng.erp.dashboard.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Data
@Schema(description = "工作台库存状态")
public class DashboardInventoryStatusVO {

    @Schema(description = "库存状态分布")
    private List<Distribution> distribution = new ArrayList<>();
    @Schema(description = "库存风险记录预览")
    private RiskPreview riskPreview = new RiskPreview();
    @Schema(description = "访问状态")
    private DashboardSectionAccessVO access;

    @Data
    @Schema(description = "库存状态分布项")
    public static class Distribution {
        @Schema(description = "库存状态")
        private String status;
        @Schema(description = "仓库产品库存记录数；同一产品在不同仓库分别计数")
        private long recordCount;
    }

    @Data
    @Schema(description = "库存风险记录预览")
    public static class RiskPreview {
        @Schema(description = "按风险缺口排序的前 5 条记录")
        private List<Item> items = new ArrayList<>();
        @Schema(description = "是否还有更多风险记录")
        private boolean hasMore;
    }

    @Data
    @Schema(description = "库存风险预览项")
    public static class Item {
        @JsonSerialize(using = ToStringSerializer.class)
        @Schema(description = "对应 warehouse_stock.id，BIGINT 按字符串传输")
        private Long stockId;
        private String productCode;
        private String productName;
        private String warehouseName;
        private String unitName;
        @Schema(description = "产品数量小数位，取自 product.quantity_precision；前端按此固定展示风险库存数量", minimum = "0", maximum = "2")
        private Integer quantityPrecision;
        @Schema(description = "可用库存，等于 stock_qty - locked_qty")
        private BigDecimal availableQty;
        private BigDecimal safetyStockQty;
        @Schema(allowableValues = {"OUT_OF_STOCK", "NO_AVAILABLE", "LOW_STOCK"})
        private String severity;
    }
}