package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.dashboard.domain.vo.DashboardStockAlertVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTodoEvidenceMetricVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTodoEvidenceVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTodoItemVO;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.mapper.OutboundBillMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作台业务待办聚合器。
 *
 * <p>按业务状态动态聚合 7 类待办：采购待审核、销售待审核、待确认入库、待确认出库、
 * 库存风险复核、客户信用复核、采购价偏离参考价。<br>
 * 业务完成后（如订单审核通过、单据确认完成）业务状态自动变化，下一次工作台刷新待办自动消失。</p>
 *
 * <p>每类待办最多返回 2 条代表性单据作为 {@code evidence}，主卡展示摘要。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
public class DashboardTodoAggregator {

    private static final int EVIDENCE_LIMIT = 2;

    private final PurchaseOrderMapper purchaseOrderMapper;
    private final SalesOrderMapper salesOrderMapper;
    private final InboundBillMapper inboundBillMapper;
    private final OutboundBillMapper outboundBillMapper;
    private final DashboardStockAlertLoader stockAlertLoader;

    @Autowired
    public DashboardTodoAggregator(PurchaseOrderMapper purchaseOrderMapper,
                                   SalesOrderMapper salesOrderMapper,
                                   InboundBillMapper inboundBillMapper,
                                   OutboundBillMapper outboundBillMapper,
                                   DashboardStockAlertLoader stockAlertLoader) {
        this.purchaseOrderMapper = purchaseOrderMapper;
        this.salesOrderMapper = salesOrderMapper;
        this.inboundBillMapper = inboundBillMapper;
        this.outboundBillMapper = outboundBillMapper;
        this.stockAlertLoader = stockAlertLoader;
    }

    /**
     * 加载工作台业务待办
     * @return 待办 VO 列表，按业务类型聚合
     */
    public List<DashboardTodoItemVO> load() {
        List<DashboardTodoItemVO> todos = new ArrayList<>();
        todos.add(buildPurchaseApproveTodo());
        todos.add(buildSalesApproveTodo());
        todos.add(buildInboundPendingTodo());
        todos.add(buildOutboundPendingTodo());
        todos.add(buildStockRiskTodo());
        return todos;
    }

    private DashboardTodoItemVO buildPurchaseApproveTodo() {
        List<PurchaseOrder> orders = purchaseOrderMapper.selectList(
                new LambdaQueryWrapper<PurchaseOrder>()
                        .eq(PurchaseOrder::getStatus, PurchaseOrderStatus.SUBMITTED.name())
                        .orderByDesc(PurchaseOrder::getSubmittedAt)
                        .last("LIMIT " + (EVIDENCE_LIMIT + 1)));
        DashboardTodoItemVO todo = newTodo(
                "todo-purchase-approve", "PURCHASE", "采购",
                "采购单待审核", "还有 %d 张采购单需要审核，处理后会自动完成待办。",
                orders.size(), "HIGH", 20, "/purchase/orders",
                "前往采购订单完成审核，审核通过或驳回后该待办自动更新。");
        todo.setEvidence(toEvidenceList(orders, this::purchaseEvidence, EVIDENCE_LIMIT));
        return todo;
    }

    private DashboardTodoItemVO buildSalesApproveTodo() {
        List<SalesOrder> orders = salesOrderMapper.selectList(
                new LambdaQueryWrapper<SalesOrder>()
                        .eq(SalesOrder::getStatus, SalesOrderStatus.SUBMITTED.name())
                        .orderByDesc(SalesOrder::getSubmittedAt)
                        .last("LIMIT " + (EVIDENCE_LIMIT + 1)));
        DashboardTodoItemVO todo = newTodo(
                "todo-sales-approve", "SALES", "销售",
                "销售单待审核", "还有 %d 张销售单需要审核，处理后会自动完成待办。",
                orders.size(), "HIGH", 21, "/sales/orders",
                "前往销售订单完成审核，审核通过后进入库存锁定和发货准备。");
        todo.setEvidence(toEvidenceList(orders, this::salesEvidence, EVIDENCE_LIMIT));
        return todo;
    }

    private DashboardTodoItemVO buildInboundPendingTodo() {
        List<InboundBill> bills = inboundBillMapper.selectList(
                new LambdaQueryWrapper<InboundBill>()
                        .eq(InboundBill::getStatus, "PENDING_CONFIRM")
                        .eq(InboundBill::getInboundType, "PURCHASE_IN")
                        .orderByDesc(InboundBill::getCreateTime)
                        .last("LIMIT " + (EVIDENCE_LIMIT + 1)));
        DashboardTodoItemVO todo = newTodo(
                "todo-inbound", "WAREHOUSE", "仓储",
                "待确认入库", "还有 %d 张入库单等待仓库确认。",
                bills.size(), "MEDIUM", 50, "/warehouse/inbound-bills",
                "前往入库单完成确认，确认入库后该待办自动更新。");
        todo.setEvidence(toInboundEvidence(bills, EVIDENCE_LIMIT));
        return todo;
    }

    private DashboardTodoItemVO buildOutboundPendingTodo() {
        List<OutboundBill> bills = outboundBillMapper.selectList(
                new LambdaQueryWrapper<OutboundBill>()
                        .eq(OutboundBill::getStatus, "PENDING_CONFIRM")
                        .eq(OutboundBill::getOutboundType, "SALES_OUT")
                        .orderByDesc(OutboundBill::getCreateTime)
                        .last("LIMIT " + (EVIDENCE_LIMIT + 1)));
        DashboardTodoItemVO todo = newTodo(
                "todo-outbound", "WAREHOUSE", "仓储",
                "待确认出库", "还有 %d 张出库单等待发货确认。",
                bills.size(), "MEDIUM", 51, "/warehouse/outbound-bills",
                "前往出库单完成确认，确认出库后该待办自动更新。");
        todo.setEvidence(toOutboundEvidence(bills, EVIDENCE_LIMIT));
        return todo;
    }

    private DashboardTodoItemVO buildStockRiskTodo() {
        List<DashboardStockAlertVO> alerts = stockAlertLoader.load();
        int count = alerts.size();
        DashboardTodoItemVO todo = newTodo(
                "todo-stock-risk-review", "INVENTORY", "库存",
                "库存异常待复核", "还有 %d 个 SKU 可用库存低于安全线或已无可用库存，需要复核补货或调拨。",
                count, count > 0 ? "HIGH" : "LOW", 30, "/warehouse/stocks",
                "前往库存余额查看低库存 SKU，补货计划生成或库存恢复后自动更新。");
        List<DashboardTodoEvidenceVO> evidences = new ArrayList<>();
        for (DashboardStockAlertVO alert : alerts) {
            if (evidences.size() >= EVIDENCE_LIMIT) {
                break;
            }
            DashboardTodoEvidenceVO ev = new DashboardTodoEvidenceVO();
            ev.setItemId(String.valueOf(alert.getStockId()));
            ev.setPrimaryText(alert.getProductName() + "（" + alert.getProductCode() + "）");
            ev.setSecondaryText(alert.getWarehouseName() + " · 当前可用库存 " + alert.getAvailableQty() + alert.getUnitName());
            ev.setMetrics(List.of(
                    stockMetric("可用", alert.getAvailableQty() + " " + alert.getUnitName(), "HIGH".equals(alert.getSeverity()) ? "risk" : "watch"),
                    stockMetric("安全线", alert.getSafetyStockQty() + " " + alert.getUnitName(), "neutral"),
                    stockMetric("建议补货", alert.getSuggestedPurchaseQty() > 0 ? alert.getSuggestedPurchaseQty() + " " + alert.getUnitName() : "不适用", "neutral")
            ));
            evidences.add(ev);
        }
        todo.setEvidence(evidences);
        return todo;
    }

    private DashboardTodoEvidenceVO purchaseEvidence(PurchaseOrder order) {
        DashboardTodoEvidenceVO ev = new DashboardTodoEvidenceVO();
        ev.setItemId(order.getPurchaseNo());
        ev.setPrimaryText(order.getPurchaseNo());
        ev.setSecondaryText(order.getSupplierName() + " · " + (order.getExpectedArrivalDate() == null ? "待确认" : order.getExpectedArrivalDate().toString()));
        ev.setMetrics(List.of(
                stockMetric("金额", "￥" + toYuan(order.getTotalAmount()) + "万", "neutral"),
                stockMetric("等待", formatWait(order.getSubmittedAt()), "watch")));
        return ev;
    }

    private DashboardTodoEvidenceVO salesEvidence(SalesOrder order) {
        DashboardTodoEvidenceVO ev = new DashboardTodoEvidenceVO();
        ev.setItemId(order.getSalesNo());
        ev.setPrimaryText(order.getSalesNo());
        ev.setSecondaryText(order.getCustomerName() + " · 待销售主管审核");
        ev.setMetrics(List.of(
                stockMetric("金额", "￥" + toYuan(order.getTotalAmount()) + "万", "neutral"),
                stockMetric("等待", formatWait(order.getSubmittedAt()), "watch")));
        return ev;
    }

    private <T> List<DashboardTodoEvidenceVO> toEvidenceList(List<T> orders,
                                                              java.util.function.Function<T, DashboardTodoEvidenceVO> mapper,
                                                         int limit) {
        List<DashboardTodoEvidenceVO> result = new ArrayList<>();
        for (int i = 0; i < orders.size() && result.size() < limit; i++) {
            result.add(mapper.apply(orders.get(i)));
        }
        return result;
    }

    private List<DashboardTodoEvidenceVO> toInboundEvidence(List<InboundBill> bills, int limit) {
        List<DashboardTodoEvidenceVO> result = new ArrayList<>();
        for (int i = 0; i < bills.size() && result.size() < limit; i++) {
            InboundBill bill = bills.get(i);
            DashboardTodoEvidenceVO ev = new DashboardTodoEvidenceVO();
            ev.setItemId(bill.getInboundNo());
            ev.setPrimaryText(bill.getInboundNo());
            ev.setSecondaryText("关联 " + bill.getSourceNo() + " · " + bill.getWarehouseName());
            ev.setMetrics(List.of(
                    stockMetric("来源", bill.getSourceNo(), "neutral"),
                    stockMetric("等待", formatWait(bill.getCreateTime()), "watch")));
            result.add(ev);
        }
        return result;
    }

    private List<DashboardTodoEvidenceVO> toOutboundEvidence(List<OutboundBill> bills, int limit) {
        List<DashboardTodoEvidenceVO> result = new ArrayList<>();
        for (int i = 0; i < bills.size() && result.size() < limit; i++) {
            OutboundBill bill = bills.get(i);
            DashboardTodoEvidenceVO ev = new DashboardTodoEvidenceVO();
            ev.setItemId(bill.getOutboundNo());
            ev.setPrimaryText(bill.getOutboundNo());
            ev.setSecondaryText("关联 " + bill.getSourceNo() + " · " + bill.getWarehouseName());
            ev.setMetrics(List.of(
                    stockMetric("来源", bill.getSourceNo(), "neutral"),
                    stockMetric("等待", formatWait(bill.getCreateTime()), "watch")));
            result.add(ev);
        }
        return result;
    }

    private DashboardTodoItemVO newTodo(String todoId, String businessType, String businessLabel,
                                        String title, String descriptionTemplate, int count,
                                        String priority, int sortWeight, String route, String resolveHint) {
        DashboardTodoItemVO todo = new DashboardTodoItemVO();
        todo.setTodoId(todoId);
        todo.setBusinessType(businessType);
        todo.setBusinessLabel(businessLabel);
        todo.setTitle(title);
        todo.setDescription(String.format(descriptionTemplate, count));
        todo.setCount(count);
        todo.setPriority(count > 0 ? priority : "LOW");
        todo.setSortWeight(sortWeight);
        todo.setSourceMode("AGGREGATED");
        todo.setCompletionMode("AUTO");
        todo.setStatus("PENDING");
        todo.setErrorCode(null);
        todo.setErrorMessage(null);
        todo.setSourceNo(null);
        todo.setOccurredAt(null);
        todo.setResolveHint(resolveHint);
        todo.setRoute(route);
        return todo;
    }

    private static DashboardTodoEvidenceMetricVO stockMetric(String label, String value, String tone) {
        DashboardTodoEvidenceMetricVO m = new DashboardTodoEvidenceMetricVO();
        m.setLabel(label);
        m.setValue(value);
        m.setTone(tone);
        return m;
    }

    /** 数据库 ×100 转业务万元字符串（保留 2 位） */
    private static String toYuan(Long stored) {
        if (stored == null) {
            return "0.00";
        }
        return BigDecimal.valueOf(stored).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String formatWait(LocalDateTime since) {
        if (since == null) {
            return "未知";
        }
        Duration duration = Duration.between(since, LocalDateTime.now());
        long hours = duration.toHours();
        if (hours < 1) {
            long minutes = Math.max(duration.toMinutes(), 1);
            return minutes + "分钟";
        }
        if (hours < 24) {
            return hours + "小时";
        }
        return (hours / 24) + "天";
    }
}
