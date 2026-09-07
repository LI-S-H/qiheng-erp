package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.dashboard.domain.todo.enums.DashboardTodoDetailModel;
import com.qiheng.erp.dashboard.domain.todo.enums.DashboardTodoType;
import com.qiheng.erp.dashboard.domain.todo.enums.DashboardTodoWaitLevel;
import com.qiheng.erp.dashboard.domain.inventory.vo.DashboardStockAlertVO;
import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoItemVO;
import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoDocumentDetailVO;
import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoDocumentItemVO;
import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoStockRiskDetailVO;
import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoStockRiskItemVO;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.mapper.OutboundBillMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * 工作台业务待办聚合器。
 *
 * <p>按业务状态动态聚合采购、销售、仓储和库存待办，并按当前用户权限裁剪。
 * 每类待办只返回有限条代表性业务事实，详情由 {@code detail.model} 决定字段与卡片布局，
 * 不能把供应商、客户、等待时长等事实拼接进通用文本字段。</p>
 *
 * <p>业务完成后对应状态变化，下一次工作台刷新会自动移除待办；系统异常由
 * {@link DashboardSystemExceptionLoader} 单独加载。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
@RequiredArgsConstructor
public class DashboardTodoAggregator {

    private static final int DETAIL_ITEM_LIMIT = 2;

    private final DashboardPermissionGuard permissionGuard;
    private final PurchaseOrderMapper purchaseOrderMapper;
    private final SalesOrderMapper salesOrderMapper;
    private final ReturnOrderMapper returnOrderMapper;
    private final InboundBillMapper inboundBillMapper;
    private final OutboundBillMapper outboundBillMapper;
    private final DashboardStockAlertLoader stockAlertLoader;

    /** 加载采购单待审核待办。 */
    public List<DashboardTodoItemVO> loadPurchaseTodos(LoginUser user) {
        if (!permissionGuard.canManagePurchase(user))
            return Collections.emptyList();
        long count = purchaseOrderMapper.selectCount(new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getStatus, PurchaseOrderStatus.SUBMITTED.name()));
        if (count == 0)
            return Collections.emptyList();
        List<PurchaseOrder> orders = purchaseOrderMapper.selectList(new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getStatus, PurchaseOrderStatus.SUBMITTED.name())
                .orderByAsc(PurchaseOrder::getSubmittedAt)
                .last("LIMIT " + DETAIL_ITEM_LIMIT));
        DashboardTodoItemVO todo = newTodo(DashboardTodoType.PURCHASE_APPROVE, toTodoCount(count));
        todo.setDetail(documentDetail(DashboardTodoDetailModel.PURCHASE_ORDER_APPROVAL, toDocumentItems(orders, this::purchaseDocument)));
        return List.of(todo);
    }

    /** 加载销售单待审核待办。 */
    public List<DashboardTodoItemVO> loadSalesTodos(LoginUser user) {
        if (!permissionGuard.canManageSales(user))
            return Collections.emptyList();
        long count = salesOrderMapper.selectCount(new LambdaQueryWrapper<SalesOrder>()
                .eq(SalesOrder::getStatus, SalesOrderStatus.SUBMITTED.name()));
        if (count == 0)
            return Collections.emptyList();
        List<SalesOrder> orders = salesOrderMapper.selectList(new LambdaQueryWrapper<SalesOrder>()
                .eq(SalesOrder::getStatus, SalesOrderStatus.SUBMITTED.name())
                .orderByAsc(SalesOrder::getSubmittedAt)
                .last("LIMIT " + DETAIL_ITEM_LIMIT));
        DashboardTodoItemVO todo = newTodo(DashboardTodoType.SALES_APPROVE, toTodoCount(count));
        todo.setDetail(documentDetail(DashboardTodoDetailModel.SALES_ORDER_APPROVAL, toDocumentItems(orders, this::salesDocument)));
        return List.of(todo);
    }

    /** 加载待确认入库待办。 */
    public List<DashboardTodoItemVO> loadInboundTodos(LoginUser user) {
        if (!permissionGuard.canManageWarehouse(user))
            return Collections.emptyList();
        long count = inboundBillMapper.selectCount(new LambdaQueryWrapper<InboundBill>().eq(InboundBill::getStatus, "PENDING_CONFIRM"));
        if (count == 0)
            return Collections.emptyList();
        List<InboundBill> bills = inboundBillMapper.selectList(new LambdaQueryWrapper<InboundBill>()
                .eq(InboundBill::getStatus, "PENDING_CONFIRM").orderByAsc(InboundBill::getCreateTime).last("LIMIT " + DETAIL_ITEM_LIMIT));
        DashboardTodoItemVO todo = newTodo(DashboardTodoType.INBOUND_PENDING, toTodoCount(count));
        todo.setDetail(documentDetail(DashboardTodoDetailModel.INBOUND_CONFIRM, toDocumentItems(bills, this::inboundDocument)));
        return List.of(todo);
    }

    /** 加载待确认出库待办。 */
    public List<DashboardTodoItemVO> loadOutboundTodos(LoginUser user) {
        if (!permissionGuard.canManageWarehouse(user))
            return Collections.emptyList();
        long count = outboundBillMapper.selectCount(new LambdaQueryWrapper<OutboundBill>().eq(OutboundBill::getStatus, "PENDING_CONFIRM"));
        if (count == 0)
            return Collections.emptyList();
        List<OutboundBill> bills = outboundBillMapper.selectList(new LambdaQueryWrapper<OutboundBill>()
                .eq(OutboundBill::getStatus, "PENDING_CONFIRM").orderByAsc(OutboundBill::getCreateTime).last("LIMIT " + DETAIL_ITEM_LIMIT));
        DashboardTodoItemVO todo = newTodo(DashboardTodoType.OUTBOUND_PENDING, toTodoCount(count));
        todo.setDetail(documentDetail(DashboardTodoDetailModel.OUTBOUND_CONFIRM, toDocumentItems(bills, this::outboundDocument)));
        return List.of(todo);
    }

    /** 加载库存异常复核待办。 */
    public List<DashboardTodoItemVO> loadStockRiskTodos(LoginUser user) {
        if (!permissionGuard.canViewWarehouse(user))
            return Collections.emptyList();
        List<DashboardStockAlertVO> alerts = stockAlertLoader.load();
        if (alerts.isEmpty())
            return Collections.emptyList();
        DashboardTodoItemVO todo = newTodo(DashboardTodoType.STOCK_RISK_REVIEW, alerts.size());
        DashboardTodoStockRiskDetailVO detail = new DashboardTodoStockRiskDetailVO();
        detail.setModel(DashboardTodoDetailModel.STOCK_RISK_REVIEW);
        detail.setItems(toStockRiskItems(alerts));
        todo.setDetail(detail);
        return List.of(todo);
    }

    /** 加载采购退货待审核待办。 */
    public List<DashboardTodoItemVO> loadPurchaseReturnTodos(LoginUser user) {
        if (!permissionGuard.canManagePurchase(user))
            return Collections.emptyList();
        return loadReturnTodos(ReturnType.PURCHASE_RETURN, DashboardTodoType.PURCHASE_RETURN_APPROVE, DashboardTodoDetailModel.PURCHASE_RETURN_APPROVAL);
    }

    /** 加载销售退货待审核待办。 */
    public List<DashboardTodoItemVO> loadSalesReturnTodos(LoginUser user) {
        if (!permissionGuard.canManageSales(user)) return Collections.emptyList();
        return loadReturnTodos(ReturnType.SALES_RETURN, DashboardTodoType.SALES_RETURN_APPROVE, DashboardTodoDetailModel.SALES_RETURN_APPROVAL);
    }

    /** 加载退货待审核待办。 */
    private List<DashboardTodoItemVO> loadReturnTodos(ReturnType returnType, DashboardTodoType todoType, DashboardTodoDetailModel detailModel) {
        long count = returnOrderMapper.selectCount(new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getReturnType, returnType.name()).eq(ReturnOrder::getStatus, ReturnStatus.SUBMITTED.name()));
        if (count == 0)
            return Collections.emptyList();
        List<ReturnOrder> orders = returnOrderMapper.selectList(new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getReturnType, returnType.name()).eq(ReturnOrder::getStatus, ReturnStatus.SUBMITTED.name())
                .orderByAsc(ReturnOrder::getSubmittedAt).last("LIMIT " + DETAIL_ITEM_LIMIT));
        DashboardTodoItemVO todo = newTodo(todoType, toTodoCount(count));
        todo.setPriority(priorityForWait(orders.isEmpty() ? null : orders.getFirst().getSubmittedAt()));
        todo.setDetail(documentDetail(detailModel, toDocumentItems(orders, this::returnDocument)));
        return List.of(todo);
    }

    /** 加载采购待审核待办。 */
    private DashboardTodoDocumentItemVO purchaseDocument(PurchaseOrder order) {
        return documentItem(order.getPurchaseNo(), null, order.getSupplierName(), order.getTotalAmount(), order.getSubmittedAt(), order.getStatus());
    }

    /** 加载销售待审核待办。 */
    private DashboardTodoDocumentItemVO salesDocument(SalesOrder order) {
        return documentItem(order.getSalesNo(), null, order.getCustomerName(), order.getTotalAmount(), order.getSubmittedAt(), order.getStatus());
    }

    /** 加载退货待审核待办。 */
    private DashboardTodoDocumentItemVO returnDocument(ReturnOrder order) {
        return documentItem(order.getReturnNo(), order.getSourceOrderNo(), order.getPartyName(), order.getTotalAmount(), order.getSubmittedAt(), order.getStatus());
    }

    /** 加载入库待审核待办。 */
    private DashboardTodoDocumentItemVO inboundDocument(InboundBill bill) {
        return documentItem(bill.getInboundNo(), bill.getSourceNo(), bill.getWarehouseName(), null, bill.getCreateTime(), bill.getStatus());
    }

    /** 加载出库待审核待办。 */
    private DashboardTodoDocumentItemVO outboundDocument(OutboundBill bill) {
        return documentItem(bill.getOutboundNo(), bill.getSourceNo(), bill.getWarehouseName(), null, bill.getCreateTime(), bill.getStatus());
    }

    /** 加载待审核待办。 */
    private DashboardTodoDocumentItemVO documentItem(String documentNo, String sourceDocumentNo, String counterpartyName, Long amountFen, LocalDateTime pendingSince, String documentStatus) {
        DashboardTodoDocumentItemVO item = new DashboardTodoDocumentItemVO();
        item.setDocumentNo(documentNo);
        item.setSourceDocumentNo(sourceDocumentNo);
        item.setCounterpartyName(counterpartyName);
        item.setAmountFen(amountFen);
        item.setWaitHours(waitHours(pendingSince));
        item.setWaitLevel(waitLevel(pendingSince));
        item.setDocumentStatus(documentStatus);
        return item;
    }

    /** 加载待审核待办详情。 */
    private DashboardTodoDocumentDetailVO documentDetail(DashboardTodoDetailModel model, List<DashboardTodoDocumentItemVO> items) {
        DashboardTodoDocumentDetailVO detail = new DashboardTodoDocumentDetailVO();
        detail.setModel(model);
        detail.setItems(items);
        return detail;
    }

    /** 加载库存危险详情。 */
    private List<DashboardTodoStockRiskItemVO> toStockRiskItems(List<DashboardStockAlertVO> alerts) {
        List<DashboardTodoStockRiskItemVO> items = new ArrayList<>();
        for (DashboardStockAlertVO alert : alerts) {
            if (items.size() >= DETAIL_ITEM_LIMIT) break;
            DashboardTodoStockRiskItemVO item = new DashboardTodoStockRiskItemVO();
            item.setId(String.valueOf(alert.getStockId()));
            item.setProductCode(alert.getProductCode());
            item.setProductName(alert.getProductName());
            item.setWarehouseName(alert.getWarehouseName());
            item.setUnitName(alert.getUnitName());
            item.setAvailableQty(alert.getAvailableQty());
            item.setSafetyStockQty(alert.getSafetyStockQty());
            item.setSuggestedPurchaseQty(alert.getSuggestedPurchaseQty());
            item.setSeverity(alert.getSeverity());
            items.add(item);
        }
        return items;
    }

    /** 构建待审核待办列表。 */
    private <T> List<DashboardTodoDocumentItemVO> toDocumentItems(List<T> source, Function<T, DashboardTodoDocumentItemVO> mapper) {
        List<DashboardTodoDocumentItemVO> items = new ArrayList<>();
        for (T item : source) {
            if (items.size() >= DETAIL_ITEM_LIMIT) break;
            items.add(mapper.apply(item));
        }
        return items;
    }

    /** 构建待办项。 */
    private DashboardTodoItemVO newTodo(DashboardTodoType type, int count) {
        DashboardTodoItemVO todo = new DashboardTodoItemVO();
        todo.setTodoId(type.getTodoId());
        todo.setBusinessType(type.getBusinessType());
        todo.setBusinessLabel(type.getBusinessLabel());
        todo.setTitle(type.getTitle());
        todo.setDescription(String.format(type.getDescriptionTemplate(), count));
        todo.setCount(count);
        todo.setPriority(count > 0 ? type.getPriority() : "LOW");
        todo.setSortWeight(type.getSortWeight());
        todo.setCompletionMode(type == DashboardTodoType.SYSTEM_EXCEPTION ? "TRACKED" : "AUTO");
        todo.setResolveHint(type.getResolveHint());
        return todo;
    }

    /** 转换待办数量，防止极端数据导致 int 溢出。 */
    private static int toTodoCount(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    /** 等待时长分级：超过三天为 HIGH，超过一天为 MEDIUM，其余为 LOW。 */
    private static String priorityForWait(LocalDateTime pendingSince) {
        return switch (waitLevel(pendingSince)) {
            case OVERDUE -> "HIGH";
            case WARNING -> "MEDIUM";
            default -> "LOW";
        };
    }

    /** 等待时长分级：超过三天为 OVERDUE，超过一天为 WARNING，其余为 NORMAL。 */
    private static DashboardTodoWaitLevel waitLevel(LocalDateTime pendingSince) {
        long hours = waitHours(pendingSince);
        if (hours >= 72) return DashboardTodoWaitLevel.OVERDUE;
        if (hours >= 24) return DashboardTodoWaitLevel.WARNING;
        return DashboardTodoWaitLevel.NORMAL;
    }

    /** 计算待办待处理时间（小时）。 */
    private static long waitHours(LocalDateTime pendingSince) {
        if (pendingSince == null) return 0L;
        return Math.max(Duration.between(pendingSince, LocalDateTime.now()).toHours(), 0L);
    }
}