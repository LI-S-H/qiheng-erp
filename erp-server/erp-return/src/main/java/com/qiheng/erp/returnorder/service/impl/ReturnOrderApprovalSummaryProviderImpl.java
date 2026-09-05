package com.qiheng.erp.returnorder.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrderItem;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.port.ReturnOrderApprovalSummaryProvider;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceItemApprovalSummary;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceOrderApprovalSummary;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.mapper.ReturnOrderItemMapper;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 退货审批汇总查询实现。
 *
 * <p>审批数量和来源单价均由退货单明细持久化，汇总时不读取可变的商品档案价格，
 * 保证经营指标与退货审批事实可追溯。</p>
 */
@Service
@RequiredArgsConstructor
public class ReturnOrderApprovalSummaryProviderImpl implements ReturnOrderApprovalSummaryProvider {

    private static final Set<String> EFFECTIVE_STATUSES = Set.of(
            ReturnStatus.APPROVED.name(),
            ReturnStatus.PARTIAL_EXECUTED.name(),
            ReturnStatus.COMPLETED.name()
    );

    private final ReturnOrderMapper returnOrderMapper;
    private final ReturnOrderItemMapper returnOrderItemMapper;

    @Override
    public Map<Long, ReturnSourceOrderApprovalSummary> summarize(ReturnType returnType,
                                                                  Collection<Long> sourceOrderIds) {
        if (sourceOrderIds == null || sourceOrderIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = sourceOrderIds.stream().filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        // 1. 加载退货单
        List<ReturnOrder> orders = returnOrderMapper.selectList(new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getReturnType, returnType.name())
                .in(ReturnOrder::getSourceOrderId, ids));
        if (orders.isEmpty()) {
            return Map.of();
        }
        // 2. 按来源单分组(来源单ID -> 退货单列表)
        Map<Long, List<ReturnOrder>> ordersBySource = orders.stream()
                .collect(Collectors.groupingBy(ReturnOrder::getSourceOrderId));
        // 3. 加载有效退货单明细
        List<Long> effectiveOrderIds = orders.stream()
                .filter(order -> EFFECTIVE_STATUSES.contains(order.getStatus()))
                .map(ReturnOrder::getId)
                .toList();
        // 4. 按退货单分组(退货单ID -> 退货单明细列表)
        Map<Long, List<ReturnOrderItem>> itemsByOrder = effectiveOrderIds.isEmpty()
                ? Map.of()
                : returnOrderItemMapper.selectList(new LambdaQueryWrapper<ReturnOrderItem>()
                                .in(ReturnOrderItem::getReturnOrderId, effectiveOrderIds))
                        .stream().collect(Collectors.groupingBy(ReturnOrderItem::getReturnOrderId));
        // 5. 汇总审批数量
        Map<Long, ReturnSourceOrderApprovalSummary> result = new HashMap<>();
        for (Map.Entry<Long, List<ReturnOrder>> entry : ordersBySource.entrySet()) {
            List<ReturnOrder> sourceOrders = entry.getValue();
            List<ReturnOrder> effectiveOrders = sourceOrders.stream()
                    .filter(order -> EFFECTIVE_STATUSES.contains(order.getStatus()))
                    .toList();
            Map<Long, ItemAccumulator> accumulators = new HashMap<>();
            for (ReturnOrder order : effectiveOrders) {
                for (ReturnOrderItem item : itemsByOrder.getOrDefault(order.getId(), List.of())) {
                    long approvedQty = item.getApprovedQty() == null ? 0L : item.getApprovedQty();
                    if (approvedQty <= 0) {
                        continue;
                    }
                    ItemAccumulator accumulator = accumulators.computeIfAbsent(item.getSourceOrderItemId(),
                            ignored -> new ItemAccumulator());
                    accumulator.approvedQty += approvedQty;
                    accumulator.approvedAmount += calculateApprovedAmount(approvedQty, item.getUnitPrice());
                }
            }
            Map<Long, ReturnSourceItemApprovalSummary> itemSummaries = accumulators.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, item -> new ReturnSourceItemApprovalSummary(
                            item.getKey(), item.getValue().approvedQty, item.getValue().approvedAmount)));
            long approvedAmount = accumulators.values().stream()
                    .mapToLong(accumulator -> accumulator.approvedAmount)
                    .sum();
            result.put(entry.getKey(), new ReturnSourceOrderApprovalSummary(
                    entry.getKey(),
                    sourceOrders.size(),
                    effectiveOrders.size(),
                    approvedAmount,
                    Map.copyOf(itemSummaries)
            ));
        }
        return result;
    }

    /** 按退货明细快照单价计算审批金额，沿用数量和金额的统一放大规则。 */
    private long calculateApprovedAmount(long approvedQty, Long unitPrice) {
        long price = unitPrice == null ? 0L : unitPrice;
        BigDecimal amount = QtyUtil.toDecimal(approvedQty).multiply(QtyUtil.toDecimal(price));
        return QtyUtil.toStored(amount);
    }

    /** 单次来源订单汇总过程中的可变累加器，避免将状态混入跨模块只读契约。 */
    private static class ItemAccumulator {
        private long approvedQty;
        private long approvedAmount;
    }
}
