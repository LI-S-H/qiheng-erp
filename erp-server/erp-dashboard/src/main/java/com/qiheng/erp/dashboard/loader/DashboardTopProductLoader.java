package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.cache.TopProductRankCache;
import com.qiheng.erp.dashboard.cache.TopProductRankCache.RankEntry;
import com.qiheng.erp.dashboard.cache.TopProductRankRefresher;
import com.qiheng.erp.dashboard.domain.vo.DashboardTopProductVO;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作台销售商品排行聚合器。
 *
 * <p>排行数据（近 30 天累计金额与数量）由 {@link TopProductRankCache} 维护，
 * 本类负责从 Redis 取前 N 个商品后实时补齐商品资料（编码、名称、可用库存）。</p>
 *
 * @author Li
 * @since 2026-09-02
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardTopProductLoader {

    private static final int TOP_LIMIT = 10;

    /** 从 Redis 多取一倍应对凌晨后被新删除的商品淘汰：渲染前过滤已删除，凑齐有效数即停 */
    private static final int FETCH_FACTOR = 2;

    private final TopProductRankCache rankCache;
    private final TopProductRankRefresher refresher;
    private final ProductMapper productMapper;
    private final WarehouseStockMapper warehouseStockMapper;

    /**
     * 加载销售商品排行
     * @return 销售金额降序的产品列表
     */
    public List<DashboardTopProductVO> load() {
        // 1. 从 Redis 多取 TOP_LIMIT × FETCH_FACTOR 个（多取应对凌晨后被新删除的淘汰）
        List<RankEntry> top = rankCache.readTop(TOP_LIMIT * FETCH_FACTOR);
        // 1.1 冷启动：凌晨 job 没跑过 / Redis 被清空时，首次访问触发 SQL 重建
        if (top.isEmpty()) {
            try {
                rankCache.rebuild(refresher::queryRankEntries);
                top = rankCache.readTop(TOP_LIMIT * FETCH_FACTOR);
            } catch (Exception ex) {
                log.warn("TOP 商品排行冷启动重建失败，保持空数据", ex);
            }
        }
        if (top.isEmpty()) {
            return List.of();
        }
        // 2. 收集商品 ID（readTop 已一次性带回金额+数量，无需再读 quantities）
        List<Long> productIds = new ArrayList<>(top.size());
        for (RankEntry entry : top) {
            productIds.add(entry.productId());
        }
        // 3. 实时查商品资料（已过滤逻辑删除）与可用库存（按 productId 聚合）
        Map<Long, Product> productById = loadProducts(productIds);
        Map<Long, Long> availableByProductId = loadAvailableQty(productIds);
        // 4. 组装 VO：productById 里缺失 = 已删除（凌晨后被新删）；停用商品 status=0 保留展示
        List<DashboardTopProductVO> result = new ArrayList<>(TOP_LIMIT);
        for (RankEntry entry : top) {
            if (result.size() >= TOP_LIMIT) {
                break;
            }
            Product product = productById.get(entry.productId());
            if (product == null) {
                // 已删除商品跳过；后续条目顶上补齐
                continue;
            }
            DashboardTopProductVO vo = new DashboardTopProductVO();
            vo.setProductId(entry.productId());
            vo.setProductCode(product.getProductCode());
            vo.setProductName(product.getProductName());
            vo.setSalesAmount(QtyUtil.toDecimal(entry.amount()));
            vo.setSalesQty(QtyUtil.toDecimal(entry.quantity()));
            vo.setAvailableQty(QtyUtil.toDecimal(availableByProductId.getOrDefault(entry.productId(), 0L)));
            result.add(vo);
        }
        return result;
    }

    /** 按 productId 批量查未删除商品：已删除的商品 productById 里查不到，渲染时被自然跳过 */
    private Map<Long, Product> loadProducts(List<Long> productIds) {
        List<Product> products = productMapper.selectList(
                new LambdaQueryWrapper<Product>()
                        .select(Product::getId, Product::getProductCode, Product::getProductName)
                        .in(Product::getId, productIds));
        Map<Long, Product> map = new HashMap<>(products.size() * 2);
        for (Product product : products) {
            map.put(product.getId(), product);
        }
        return map;
    }

    /** 按 productId 聚合可用库存（available = stockQty - lockedQty，跨仓库求和） */
    private Map<Long, Long> loadAvailableQty(List<Long> productIds) {
        List<WarehouseStock> stocks = warehouseStockMapper.selectList(
                new LambdaQueryWrapper<WarehouseStock>()
                        .select(WarehouseStock::getProductId, WarehouseStock::getStockQty, WarehouseStock::getLockedQty)
                        .in(WarehouseStock::getProductId, productIds));
        Map<Long, Long> map = new HashMap<>(stocks.size() * 2);
        for (WarehouseStock stock : stocks) {
            long available = nullSafe(stock.getStockQty()) - nullSafe(stock.getLockedQty());
            map.merge(stock.getProductId(), available, Long::sum);
        }
        return map;
    }

    /** 安全转换 Long 为 long，避免 NPE */
    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }
}