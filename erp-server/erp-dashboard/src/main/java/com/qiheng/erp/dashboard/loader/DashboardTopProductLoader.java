package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.domain.vo.DashboardTopProductVO;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrderItem;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderItemMapper;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作台销售商品排行聚合器。
 *
 * <p>近 30 日内过审的销售订单（{@code APPROVED / PARTIAL_OUTBOUND / OUTBOUND_DONE}），
 * 按产品聚合销售金额和销售数量，按销售金额降序取前 N 条（工作台建议最多 10 条）。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
public class DashboardTopProductLoader {

    private static final int TOP_LIMIT = 10;

    private final SalesOrderMapper salesOrderMapper;
    private final SalesOrderItemMapper salesOrderItemMapper;
    private final WarehouseStockMapper warehouseStockMapper;

    @Autowired
    public DashboardTopProductLoader(SalesOrderMapper salesOrderMapper,
                                     SalesOrderItemMapper salesOrderItemMapper,
                                     WarehouseStockMapper warehouseStockMapper) {
        this.salesOrderMapper = salesOrderMapper;
        this.salesOrderItemMapper = salesOrderItemMapper;
        this.warehouseStockMapper = warehouseStockMapper;
    }

    /**
     * 加载销售商品排行
     * @return 销售金额降序的产品列表
     */
    public List<DashboardTopProductVO> load() {
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(29L);

        List<SalesOrder> orders = salesOrderMapper.selectList(
                new LambdaQueryWrapper<SalesOrder>()
                        .select(SalesOrder::getId)
                        .in(SalesOrder::getStatus,
                                SalesOrderStatus.APPROVED.name(),
                                SalesOrderStatus.PARTIAL_OUTBOUND.name(),
                                SalesOrderStatus.OUTBOUND_DONE.name())
                        .between(SalesOrder::getApprovedAt, startDate.atStartOfDay(), today.atTime(23, 59, 59)));
        if (orders.isEmpty()) {
            return new ArrayList<>();
        }
        Set<Long> orderIds = orders.stream().map(SalesOrder::getId).collect(Collectors.toSet());
        List<SalesOrderItem> items = salesOrderItemMapper.selectList(
                new LambdaQueryWrapper<SalesOrderItem>()
                        .in(SalesOrderItem::getSalesOrderId, orderIds));

        Map<Long, ProductAggregate> aggregateByProduct = new HashMap<>();
        for (SalesOrderItem item : items) {
            ProductAggregate agg = aggregateByProduct.computeIfAbsent(item.getProductId(),
                    id -> new ProductAggregate(item.getProductCode(), item.getProductName()));
            agg.salesAmount = agg.salesAmount.add(BigDecimal.valueOf(nullSafe(item.getTotalAmount())));
            agg.salesQty = agg.salesQty.add(BigDecimal.valueOf(nullSafe(item.getQuantity())));
        }

        Map<Long, Long> availableQtyByProduct = new HashMap<>();
        List<WarehouseStock> stocks = warehouseStockMapper.selectList(
                new LambdaQueryWrapper<WarehouseStock>().select(WarehouseStock::getProductId,
                        WarehouseStock::getStockQty, WarehouseStock::getLockedQty));
        for (WarehouseStock stock : stocks) {
            long available = nullSafe(stock.getStockQty()) - nullSafe(stock.getLockedQty());
            availableQtyByProduct.merge(stock.getProductId(), available, Long::sum);
        }

        List<DashboardTopProductVO> result = new ArrayList<>(aggregateByProduct.size());
        for (Map.Entry<Long, ProductAggregate> entry : aggregateByProduct.entrySet()) {
            ProductAggregate agg = entry.getValue();
            DashboardTopProductVO vo = new DashboardTopProductVO();
            vo.setProductId(entry.getKey());
            vo.setProductCode(agg.productCode);
            vo.setProductName(agg.productName);
            vo.setSalesAmount(QtyUtil.toDecimal(agg.salesAmount));
            vo.setSalesQty(QtyUtil.toDecimal(agg.salesQty));
            vo.setAvailableQty(QtyUtil.toDecimal(availableQtyByProduct.getOrDefault(entry.getKey(), 0L)));
            result.add(vo);
        }
        result.sort(Comparator.comparing(DashboardTopProductVO::getSalesAmount).reversed());
        if (result.size() > TOP_LIMIT) {
            return result.subList(0, TOP_LIMIT);
        }
        return result;
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }

    /** 产品聚合缓存对象 */
    private static final class ProductAggregate {
        private final String productCode;
        private final String productName;
        private BigDecimal salesAmount = BigDecimal.ZERO;
        private BigDecimal salesQty = BigDecimal.ZERO;

        private ProductAggregate(String productCode, String productName) {
            this.productCode = productCode;
            this.productName = productName;
        }
    }
}