package com.qiheng.erp.dashboard.domain.inventory.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 工作台库存风险 SKU VO
 * </p>
 *
 * <p>available_qty = warehouse_stock.stock_qty - warehouse_stock.locked_qty，×100 还原为业务小数展示。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@Schema(description = "工作台库存风险 SKU")
public class DashboardStockAlertVO {

    @Schema(description = "库存 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long stockId;

    @Schema(description = "产品 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "仓库 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long warehouseId;

    @Schema(description = "仓库名称")
    private String warehouseName;

    @Schema(description = "单位名称")
    private String unitName;

    @Schema(description = "可用库存数量（已锁定库存不计入），按 100 倍存储值还原为业务小数")
    private BigDecimal availableQty;

    @Schema(description = "产品安全库存数量，按 100 倍存储值还原为业务小数")
    private BigDecimal safetyStockQty;

    @Schema(description = "建议补货数量；停用产品为 0，按 100 倍存储值还原为业务小数")
    private BigDecimal suggestedPurchaseQty;

    @Schema(description = "库存健康状态，与仓库模块 InventoryHealth 口径一致", allowableValues = {"OUT_OF_STOCK", "NO_AVAILABLE", "LOW_STOCK"})
    private String severity;

    @Schema(description = "最近一次出库时间；从未出库时为 null")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime latestOutboundAt;
}