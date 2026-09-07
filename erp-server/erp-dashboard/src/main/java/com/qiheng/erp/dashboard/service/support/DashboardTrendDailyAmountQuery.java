package com.qiheng.erp.dashboard.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.event.dashboard.DashboardTrendMetric;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.domain.metric.enums.OrderMetricScope;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrderItem;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.mapper.ReturnOrderItemMapper;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 经营趋势每日原始金额查询。
 *
 * <p>本组件只负责按审批日期聚合数据库数据，不包含权限判断、Redis 读写或缓存失效逻辑，
 * 以便工作台请求与定时预热共用同一业务口径。</p>
 */
@Component
@RequiredArgsConstructor
public class DashboardTrendDailyAmountQuery {

    private final SalesOrderMapper salesOrderMapper;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final ReturnOrderMapper returnOrderMapper;
    private final ReturnOrderItemMapper returnOrderItemMapper;

    /** 查询闭区间内指定趋势维度的每日原始分值。 */
    public Map<LocalDate, Long> query(DashboardTrendMetric metric, LocalDate startDate, LocalDate endDate) {
        return switch (metric) {
            case SALES -> loadSalesAmounts(startDate, endDate);
            case PURCHASE -> loadPurchaseAmounts(startDate, endDate);
            case SALES_RETURN -> loadReturnAmounts(ReturnType.SALES_RETURN, startDate, endDate);
            case PURCHASE_RETURN -> loadReturnAmounts(ReturnType.PURCHASE_RETURN, startDate, endDate);
        };
    }

    /** 销售订单按按审批日归属，仅累计审批通过后的有效销售单。 */
    private Map<LocalDate, Long> loadSalesAmounts(LocalDate startDate, LocalDate endDate) {
        return aggregateByDay(salesOrderMapper.selectList(new LambdaQueryWrapper<SalesOrder>()
                        .in(SalesOrder::getStatus, OrderMetricScope.SALES.getStatuses())
                        .between(SalesOrder::getApprovedAt, startDate.atStartOfDay(), endDate.atTime(23, 59, 59))),
                SalesOrder::getApprovedAt, SalesOrder::getTotalAmount);
    }

    /** 采购订单按按审批日归属，仅累计审批通过后的有效采购单。 */
    private Map<LocalDate, Long> loadPurchaseAmounts(LocalDate startDate, LocalDate endDate) {
        return aggregateByDay(purchaseOrderMapper.selectList(new LambdaQueryWrapper<PurchaseOrder>()
                        .in(PurchaseOrder::getStatus, OrderMetricScope.PURCHASE.getStatuses())
                        .between(PurchaseOrder::getApprovedAt, startDate.atStartOfDay(), endDate.atTime(23, 59, 59))),
                PurchaseOrder::getApprovedAt, PurchaseOrder::getTotalAmount);
    }

    /** 退货金额按退货审批日归属，仅累计审批通过后的有效退货单。 */
    private Map<LocalDate, Long> loadReturnAmounts(ReturnType returnType, LocalDate startDate, LocalDate endDate) {
        // 1. 查询指定退货类型的所有退货单
        List<ReturnOrder> orders = returnOrderMapper.selectList(new LambdaQueryWrapper<ReturnOrder>()
            .eq(ReturnOrder::getReturnType, returnType.name())
            .in(ReturnOrder::getStatus, ReturnStatus.APPROVED.name(), ReturnStatus.PARTIAL_EXECUTED.name(),
                ReturnStatus.COMPLETED.name())
            .between(ReturnOrder::getApprovedAt, startDate.atStartOfDay(), endDate.atTime(23, 59, 59)));
        if (orders.isEmpty()) {
            return Map.of();
        }
        // 2. 构建退货单审批日期映射表(订单ID -> 审批日期)
        Map<Long, LocalDate> approvedDateByOrderId = new HashMap<>();
        for (ReturnOrder order : orders) {
            if (order.getApprovedAt() != null) {
                approvedDateByOrderId.put(order.getId(), order.getApprovedAt().toLocalDate());
            }
        }
        if (approvedDateByOrderId.isEmpty()) {
            return Map.of();
        }
        // 3. 查询指定退货单的所有退货单项
        List<ReturnOrderItem> items = returnOrderItemMapper.selectList(new LambdaQueryWrapper<ReturnOrderItem>()
                .in(ReturnOrderItem::getReturnOrderId, approvedDateByOrderId.keySet()));
        Map<LocalDate, Long> result = new HashMap<>();
        // 4. 累计审批通过后的有效退货单项
        for (ReturnOrderItem item : items) {
            LocalDate approvedDate = approvedDateByOrderId.get(item.getReturnOrderId());
            if (approvedDate == null || item.getApprovedQty() == null || item.getApprovedQty() <= 0) {
                continue;
            }
            long unitPrice = item.getUnitPrice() == null ? 0L : item.getUnitPrice();
            long amount = QtyUtil.toStored(QtyUtil.toDecimal(item.getApprovedQty())
                    .multiply(QtyUtil.toDecimal(unitPrice)));
            result.merge(approvedDate, amount, Long::sum);
        }
        return result;
    }

    /** 按审批日期聚合实体列表，仅累计审批通过后的有效记录。 */
    private static <T> Map<LocalDate, Long> aggregateByDay(List<T> entities,
                                                            Function<T, LocalDateTime> timeGetter,
                                                            Function<T, Long> amountGetter) {
        Map<LocalDate, Long> result = new HashMap<>();
        for (T entity : entities) {
            LocalDateTime approvedAt = timeGetter.apply(entity);
            Long amount = amountGetter.apply(entity);
            if (approvedAt != null && amount != null) {
                result.merge(approvedAt.toLocalDate(), amount, Long::sum);
            }
        }
        return result;
    }
}