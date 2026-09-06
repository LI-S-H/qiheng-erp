package com.qiheng.erp.warehouse.domain.warehousestock.vo;

import lombok.Data;

/** 工作台库存状态风险预览的最小查询结果。数量字段保持数据库的 x100 存储值。 */
@Data
public class InventoryRiskPreviewVo {
    /** 库存记录 ID */
    private Long stockId;
    /** 产品编码 */
    private String productCode;
    /** 产品名称 */
    private String productName;
    /** 仓库名称 */
    private String warehouseName;
    /** 单位名称 */
    private String unitName;
    /** 产品数量精度，决定工作台风险预览的数量显示位数 */
    private Integer quantityPrecision;
    /** 库存数量 */
    private Long stockQty;
    /** 已锁定库存数量 */
    private Long lockedQty;
    /** 安全库存数量 */
    private Long safetyStockQty;
}