package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.dashboard.domain.vo.DashboardStockAlertVO;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工作台库存风险 SKU 聚合器。
 *
 * <p>判定规则：{@code available_qty = warehouse_stock.stock_qty - warehouse_stock.locked_qty}，<br>
 * 若 {@code available_qty < product.safety_stock_qty} 则视为风险 SKU。<br>
 * available_qty = 0 时严重度为 HIGH；否则为 MEDIUM。<br>
 * 建议补货量 = {@code safety_stock_qty * 2 - available_qty}，产品停用时为 0。</p>
 *
 * <p>按 {@code available_qty / safety_stock_qty} 升序展示，最缺货的优先展示，最多 20 条。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
public class DashboardStockAlertLoader {

    private static final int TOP_LIMIT = 20;

    private final WarehouseStockMapper warehouseStockMapper;
    private final ProductMapper productMapper;

    @Autowired
    public DashboardStockAlertLoader(WarehouseStockMapper warehouseStockMapper,
                                     ProductMapper productMapper) {
        this.warehouseStockMapper = warehouseStockMapper;
        this.productMapper = productMapper;
    }

    /**
     * 加载库存风险 SKU
     * @return 风险 SKU VO 列表
     */
    public List<DashboardStockAlertVO> load() {
        List<WarehouseStock> stocks = warehouseStockMapper.selectList(null);
        if (stocks.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, Product> productById = productMapper.selectList(
                new LambdaQueryWrapper<Product>().eq(Product::getDeleted, 0)).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        List<DashboardStockAlertVO> alerts = new ArrayList<>();
        for (WarehouseStock stock : stocks) {
            Product product = productById.get(stock.getProductId());
            if (product == null) {
                continue;
            }
            long stockQty = stock.getStockQty() == null ? 0L : stock.getStockQty();
            long lockedQty = stock.getLockedQty() == null ? 0L : stock.getLockedQty();
            long availableQty = stockQty - lockedQty;
            long safetyQty = product.getSafetyStockQty() == null ? 0L : product.getSafetyStockQty().longValue();
            if (availableQty >= safetyQty) {
                continue;
            }
            boolean productActive = product.getStatus() != null && product.getStatus() == 1;
            long suggested = productActive ? Math.max(safetyQty * 2L - availableQty, 0L) : 0L;

            DashboardStockAlertVO vo = new DashboardStockAlertVO();
            vo.setStockId(stock.getId());
            vo.setProductId(stock.getProductId());
            vo.setProductCode(stock.getProductCode());
            vo.setProductName(stock.getProductName());
            vo.setWarehouseId(stock.getWarehouseId());
            vo.setWarehouseName(stock.getWarehouseName());
            vo.setUnitName(stock.getUnitName());
            vo.setAvailableQty(toQty(availableQty));
            vo.setSafetyStockQty(toQty(safetyQty));
            vo.setSuggestedPurchaseQty(toQty(suggested));
            vo.setSeverity(availableQty <= 0L ? "HIGH" : "MEDIUM");
            vo.setLatestOutboundAt(stock.getUpdateTime());
            alerts.add(vo);
        }
        alerts.sort(Comparator.comparingDouble((DashboardStockAlertVO vo) ->
                vo.getSafetyStockQty() == null ? 0.0 : vo.getSafetyStockQty() - vo.getAvailableQty()).reversed());
        if (alerts.size() > TOP_LIMIT) {
            return alerts.subList(0, TOP_LIMIT);
        }
        return alerts;
    }

    /** 数据库 ×100 转业务小数 */
    private static Double toQty(Long stored) {
        if (stored == null) {
            return 0.0;
        }
        return BigDecimal.valueOf(stored).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).doubleValue();
    }
}
