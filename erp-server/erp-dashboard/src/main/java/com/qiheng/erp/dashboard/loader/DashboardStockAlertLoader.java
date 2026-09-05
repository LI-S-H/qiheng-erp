package com.qiheng.erp.dashboard.loader;

import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.domain.vo.DashboardStockAlertVO;
import com.qiheng.erp.warehouse.domain.common.enums.InventoryHealth;
import com.qiheng.erp.warehouse.domain.warehousestock.vo.RiskStockVo;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 工作台库存风险 SKU 聚合器。
 *
 * <p>判定规则：{@code available_qty = warehouse_stock.stock_qty - warehouse_stock.locked_qty}，<br>
 * severity 与仓库模块 {@link InventoryHealth} 口径一致：<br>
 * - {@code stock_qty = 0} → OUT_OF_STOCK（无库存）<br>
 * - {@code available_qty = 0 且 stock_qty > 0} → NO_AVAILABLE（全锁定）<br>
 * - {@code 0 < available_qty <= safety_stock_qty} → LOW_STOCK（低库存）<br>
 * 建议补货量 = {@code safety_stock_qty * 2 - available_qty}，产品停用时为 0。</p>
 *
 * <p>SQL 层 JOIN + 过滤 + 排序 + LIMIT，避免全表扫描；按缺口降序展示，最多 20 条。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
@RequiredArgsConstructor
public class DashboardStockAlertLoader {

    private static final int TOP_LIMIT = 20;

    private final WarehouseStockMapper warehouseStockMapper;

    /**
     * 加载库存风险 SKU（最多 TOP_LIMIT 条，SQL 层排序截断）
     *
     * @return 风险 SKU VO 列表
     */
    public List<DashboardStockAlertVO> load() {
        List<RiskStockVo> rows = warehouseStockMapper.selectRiskStocks(TOP_LIMIT);
        return rows.stream().map(this::toVO).toList();
    }

    /**
     * 统计库存风险 SKU 数量（SQL 层 COUNT，不加载明细）
     *
     * @return 风险 SKU 数量
     */
    public int countRiskSkus() {
        return (int) warehouseStockMapper.countRiskStocks();
    }

    private DashboardStockAlertVO toVO(RiskStockVo row) {
        BigDecimal stockQty = nullToZero(QtyUtil.toDecimal(row.getStockQty()));
        BigDecimal lockedQty = nullToZero(QtyUtil.toDecimal(row.getLockedQty()));
        BigDecimal availableQty = stockQty.subtract(lockedQty);
        BigDecimal safetyQty = nullToZero(QtyUtil.toDecimal(row.getSafetyStockQty()));

        boolean productActive = row.getProductStatus() != null && row.getProductStatus() == 1;
        BigDecimal suggested = productActive
                ? safetyQty.multiply(BigDecimal.valueOf(2L)).subtract(availableQty).max(BigDecimal.ZERO)
                : BigDecimal.ZERO;

        DashboardStockAlertVO vo = new DashboardStockAlertVO();
        vo.setStockId(row.getStockId());
        vo.setProductId(row.getProductId());
        vo.setProductCode(row.getProductCode());
        vo.setProductName(row.getProductName());
        vo.setWarehouseId(row.getWarehouseId());
        vo.setWarehouseName(row.getWarehouseName());
        vo.setUnitName(row.getUnitName());
        vo.setAvailableQty(availableQty);
        vo.setSafetyStockQty(safetyQty);
        vo.setSuggestedPurchaseQty(suggested);
        vo.setSeverity(resolveHealth(availableQty, stockQty).name());
        vo.setLatestOutboundAt(row.getUpdateTime());
        return vo;
    }

    /**
     * 根据可用库存和当前库存派生库存健康状态，与仓库模块 {@link InventoryHealth} 口径一致。
     */
    private static InventoryHealth resolveHealth(BigDecimal availableQty, BigDecimal stockQty) {
        if (stockQty.signum() == 0) {
            return InventoryHealth.OUT_OF_STOCK;
        }
        if (availableQty.signum() == 0) {
            return InventoryHealth.NO_AVAILABLE;
        }
        return InventoryHealth.LOW_STOCK;
    }

    private static BigDecimal nullToZero(BigDecimal val) {
        return val != null ? val : BigDecimal.ZERO;
    }
}