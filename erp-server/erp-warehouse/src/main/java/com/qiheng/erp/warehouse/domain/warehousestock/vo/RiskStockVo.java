package com.qiheng.erp.warehouse.domain.warehousestock.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 库存风险 SKU 查询结果，由 warehouse_stock LEFT JOIN product 派生。
 *
 * <p>字段均为数据库原始 ×100 倍，由消费方按 QtyUtil 还原为业务小数。</p>
 */
@Data
public class RiskStockVo {

    private Long stockId;
    private Long warehouseId;
    private String warehouseName;
    private Long productId;
    private String productCode;
    private String productName;
    private String unitName;
    private Long stockQty;
    private Long lockedQty;
    private BigDecimal safetyStockQty;
    private Integer productStatus;
    private LocalDateTime updateTime;
}