package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qiheng.erp.dashboard.domain.todo.enums.DashboardTodoDetailModel;
import com.qiheng.erp.dashboard.domain.todo.enums.DashboardTodoWaitLevel;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.mapper.OutboundBillMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardTodoAggregatorTest {

    @Test
    void shouldUseFullReturnCountAndExposePurchaseReturnDetailModel() {
        DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        ReturnOrderMapper returnOrderMapper = mock(ReturnOrderMapper.class);
        LoginUser user = new LoginUser();
        when(permissionGuard.canManagePurchase(user)).thenReturn(true);
        when(returnOrderMapper.selectCount(any(Wrapper.class))).thenReturn(4L);
        when(returnOrderMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                returnOrder("PR-20260902-001", "PO-20260901-001", "供应商甲", 2),
                returnOrder("PR-20260902-002", "PO-20260901-002", "供应商乙", 80)));

        var todo = newAggregator(permissionGuard, returnOrderMapper, mock(InboundBillMapper.class), mock(OutboundBillMapper.class))
                .loadPurchaseReturnTodos(user).get(0);

        assertThat(todo.getTodoId()).isEqualTo("todo-purchase-return-approve");
        assertThat(todo.getCount()).isEqualTo(4);
        assertThat(todo.getDetail()).isInstanceOf(com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoDocumentDetailVO.class);
        var detail = (com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoDocumentDetailVO) todo.getDetail();
        assertThat(detail.getModel()).isEqualTo(DashboardTodoDetailModel.PURCHASE_RETURN_APPROVAL);
        assertThat(detail.getItems()).hasSize(2);
        assertThat(detail.getItems().get(0))
                .extracting(item -> item.getDocumentNo(), item -> item.getSourceDocumentNo(), item -> item.getCounterpartyName(), item -> item.getAmountFen(), item -> item.getWaitLevel(), item -> item.getDocumentStatus())
                .containsExactly("PR-20260902-001", "PO-20260901-001", "供应商甲", 12_345L, DashboardTodoWaitLevel.NORMAL, "SUBMITTED");
        assertThat(detail.getItems().get(1).getWaitLevel()).isEqualTo(DashboardTodoWaitLevel.OVERDUE);
    }

    @Test
    void shouldNotExposeReturnTodoWithoutDirectionalManagePermission() {
        DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        ReturnOrderMapper returnOrderMapper = mock(ReturnOrderMapper.class);

        var todos = newAggregator(permissionGuard, returnOrderMapper, mock(InboundBillMapper.class), mock(OutboundBillMapper.class))
                .loadSalesReturnTodos(new LoginUser());

        assertThat(todos).isEmpty();
        verifyNoInteractions(returnOrderMapper);
    }

    @Test
    void shouldKeepInboundCountAndDetailWithinSamePendingScope() {
        DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        InboundBillMapper inboundBillMapper = mock(InboundBillMapper.class);
        LoginUser user = new LoginUser();
        when(permissionGuard.canManageWarehouse(user)).thenReturn(true);
        when(inboundBillMapper.selectCount(any(Wrapper.class))).thenReturn(7L);
        when(inboundBillMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                inboundBill("IN-20260902-001", "PO-20260901-001", "上海主仓"),
                inboundBill("IN-20260902-002", "SR-20260901-001", "上海主仓")));

        var todo = newAggregator(permissionGuard, mock(ReturnOrderMapper.class), inboundBillMapper, mock(OutboundBillMapper.class))
                .loadInboundTodos(user).get(0);

        var detail = (com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoDocumentDetailVO) todo.getDetail();
        assertThat(todo.getCount()).isEqualTo(7);
        assertThat(detail.getModel()).isEqualTo(DashboardTodoDetailModel.INBOUND_CONFIRM);
        assertThat(detail.getItems()).extracting(item -> item.getSourceDocumentNo(), item -> item.getCounterpartyName(), item -> item.getDocumentStatus())
                .containsExactly(tuple("PO-20260901-001", "上海主仓", "PENDING_CONFIRM"), tuple("SR-20260901-001", "上海主仓", "PENDING_CONFIRM"));
    }

    @Test
    void shouldKeepOutboundCountAndDetailWithinSamePendingScope() {
        DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        OutboundBillMapper outboundBillMapper = mock(OutboundBillMapper.class);
        LoginUser user = new LoginUser();
        when(permissionGuard.canManageWarehouse(user)).thenReturn(true);
        when(outboundBillMapper.selectCount(any(Wrapper.class))).thenReturn(5L);
        when(outboundBillMapper.selectList(any(Wrapper.class))).thenReturn(List.of(
                outboundBill("OUT-20260902-001", "SO-20260901-001", "上海主仓"),
                outboundBill("OUT-20260902-002", "PR-20260901-001", "上海主仓")));

        var todo = newAggregator(permissionGuard, mock(ReturnOrderMapper.class), mock(InboundBillMapper.class), outboundBillMapper)
                .loadOutboundTodos(user).get(0);

        var detail = (com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoDocumentDetailVO) todo.getDetail();
        assertThat(todo.getCount()).isEqualTo(5);
        assertThat(detail.getModel()).isEqualTo(DashboardTodoDetailModel.OUTBOUND_CONFIRM);
        assertThat(detail.getItems()).extracting(item -> item.getSourceDocumentNo(), item -> item.getCounterpartyName(), item -> item.getDocumentStatus())
                .containsExactly(tuple("SO-20260901-001", "上海主仓", "PENDING_CONFIRM"), tuple("PR-20260901-001", "上海主仓", "PENDING_CONFIRM"));
    }

    @Test
    void shouldNotExposeWarehouseTodoWithoutManagePermission() {
        DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        InboundBillMapper inboundBillMapper = mock(InboundBillMapper.class);
        OutboundBillMapper outboundBillMapper = mock(OutboundBillMapper.class);
        DashboardTodoAggregator aggregator = newAggregator(permissionGuard, mock(ReturnOrderMapper.class), inboundBillMapper, outboundBillMapper);

        assertThat(aggregator.loadInboundTodos(new LoginUser())).isEmpty();
        assertThat(aggregator.loadOutboundTodos(new LoginUser())).isEmpty();
        verifyNoInteractions(inboundBillMapper, outboundBillMapper);
    }

    private static DashboardTodoAggregator newAggregator(DashboardPermissionGuard permissionGuard, ReturnOrderMapper returnOrderMapper,
                                                          InboundBillMapper inboundBillMapper, OutboundBillMapper outboundBillMapper) {
        return new DashboardTodoAggregator(permissionGuard, mock(PurchaseOrderMapper.class), mock(SalesOrderMapper.class),
                returnOrderMapper, inboundBillMapper, outboundBillMapper, mock(DashboardStockAlertLoader.class));
    }

    private static ReturnOrder returnOrder(String returnNo, String sourceOrderNo, String partyName, long waitHours) {
        return new ReturnOrder().setReturnNo(returnNo).setSourceOrderNo(sourceOrderNo).setPartyName(partyName)
                .setTotalAmount(12_345L).setStatus("SUBMITTED").setSubmittedAt(LocalDateTime.now().minusHours(waitHours));
    }

    private static InboundBill inboundBill(String inboundNo, String sourceNo, String warehouseName) {
        return new InboundBill().setInboundNo(inboundNo).setSourceNo(sourceNo).setWarehouseName(warehouseName)
                .setStatus("PENDING_CONFIRM").setCreateTime(LocalDateTime.now().minusHours(1));
    }

    private static OutboundBill outboundBill(String outboundNo, String sourceNo, String warehouseName) {
        return new OutboundBill().setOutboundNo(outboundNo).setSourceNo(sourceNo).setWarehouseName(warehouseName)
                .setStatus("PENDING_CONFIRM").setCreateTime(LocalDateTime.now().minusHours(1));
    }
}