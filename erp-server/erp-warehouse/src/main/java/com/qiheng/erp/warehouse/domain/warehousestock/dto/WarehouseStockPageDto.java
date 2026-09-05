package com.qiheng.erp.warehouse.domain.warehousestock.dto;

import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.warehouse.domain.common.enums.InventoryHealth;
import com.qiheng.erp.warehouse.domain.common.enums.ReservationState;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Schema(description = "库存分页查询请求")
public class WarehouseStockPageDto extends PageQuery {

    @Schema(description = "仓库ID，精确匹配")
    private String warehouseId;

    @Schema(description = "产品编码，包含匹配")
    private String productCode;

    @Schema(description = "产品名称，包含匹配")
    private String productName;

    @Schema(
            description = "库存健康状态",
            allowableValues = {
                    "NORMAL",
                    "LOW_STOCK",
                    "NO_AVAILABLE",
                    "OUT_OF_STOCK"
            }
    )
    private InventoryHealth inventoryHealth;

    @Schema(description = "是否仅返回风险库存；零库存、无可用库存或可用库存小于等于安全库存时命中，并与 inventoryHealth 按 AND 组合")
    private Boolean riskOnly;

    @Schema(
            description = "库存占用状态",
            allowableValues = {
                    "UNLOCKED",
                    "PARTIALLY_LOCKED",
                    "FULLY_LOCKED"
            }
    )
    private ReservationState reservationState;
}