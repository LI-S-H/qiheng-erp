package com.qiheng.erp.warehouse.domain.warehousestock.vo;

import lombok.Data;

/**
 * 库存健康状态分布查询结果，按 warehouse_stock.inventory_health 分组聚合。
 */
@Data
public class InventoryHealthDistributionVo {
    /** 库存健康状态枚举名，对应 InventoryHealth */
    private String inventoryHealth;
    /** 仓库产品库存记录数；同一产品在不同仓库分别计数 */
    private Long stockRecordCount;
}
