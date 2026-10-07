package com.qiheng.erp.purchase.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrderItem;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreEventTrigger;
import com.qiheng.erp.purchase.mapper.PurchaseOrderItemMapper;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.service.IPurchaseOrderItemService;
import com.qiheng.erp.purchase.service.support.ScoreRecalcPendingService;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBillItem;
import com.qiheng.erp.warehouse.mapper.InboundBillItemMapper;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.ToLongFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 采购入库快照与完全入库时间的边界测试。
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderInboundSnapshotTest {

    @Mock
    private PurchaseOrderMapper purchaseOrderMapper;
    @Mock
    private SupplierMapper supplierMapper;
    @Mock
    private PurchaseOrderItemMapper purchaseOrderItemMapper;
    @Mock
    private IPurchaseOrderItemService purchaseOrderItemService;
    @Mock
    private InboundBillMapper inboundBillMapper;
    @Mock
    private InboundBillItemMapper inboundBillItemMapper;
    @Mock
    private BillNoGenerator billNoGenerator;
    @Mock
    private ObjectProvider<ScoreRecalcPendingService> scorePendingServiceProvider;
    @Mock
    private ScoreRecalcPendingService scorePendingService;

    private PurchaseOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderServiceImpl();
        ReflectionTestUtils.setField(service, "purchaseOrderMapper", purchaseOrderMapper);
        ReflectionTestUtils.setField(service, "supplierMapper", supplierMapper);
        ReflectionTestUtils.setField(service, "purchaseOrderItemMapper", purchaseOrderItemMapper);
        ReflectionTestUtils.setField(service, "purchaseOrderItemService", purchaseOrderItemService);
        ReflectionTestUtils.setField(service, "inboundBillMapper", inboundBillMapper);
        ReflectionTestUtils.setField(service, "inboundBillItemMapper", inboundBillItemMapper);
        ReflectionTestUtils.setField(service, "billNoGenerator", billNoGenerator);
        ReflectionTestUtils.setField(service, "scorePendingServiceProvider", scorePendingServiceProvider);
    }

    @Test
    void fullyReceivedAtShouldUseFinalInboundBillConfirmedAtExactly() {
        LocalDateTime confirmedAt = LocalDateTime.of(2026, 9, 21, 14, 35, 27);
        PurchaseOrder order = purchaseOrder(10L);
        order.setPurchaseNo("PO-SOURCE-10");
        PurchaseOrderItem orderItem = purchaseOrderItem(101L, 10L, 6L);
        InboundBill bill = new InboundBill()
                .setSourceType(SourceType.PURCHASE_ORDER.name())
                .setSourceId(order.getId())
                .setConfirmedAt(confirmedAt);
        InboundBillItem inboundItem = new InboundBillItem()
                .setSourceItemId(orderItem.getId())
                .setCurrentQty(4L)
                .setProductCode("P000001")
                .setProductName("测试商品");

        when(purchaseOrderMapper.selectById(order.getId())).thenReturn(order);
        when(purchaseOrderItemService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(orderItem));
        when(purchaseOrderItemService.updateBatchById(any())).thenReturn(true);
        when(purchaseOrderMapper.updateById(any(PurchaseOrder.class))).thenReturn(1);
        when(purchaseOrderMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(supplierMapper.lockByIdForUpdate(20L)).thenReturn(20L);
        when(scorePendingServiceProvider.getIfAvailable()).thenReturn(scorePendingService);

        service.handleInboundConfirmation(bill, List.of(inboundItem));

        ArgumentCaptor<PurchaseOrder> captor = ArgumentCaptor.forClass(PurchaseOrder.class);
        verify(purchaseOrderMapper).updateById(captor.capture());
        assertEquals(PurchaseOrderStatus.INBOUND_DONE.name(), captor.getValue().getStatus());
        assertEquals(confirmedAt, captor.getValue().getFullyReceivedAt());
        ArgumentCaptor<SupplierScoreEventTrigger> triggerCaptor =
                ArgumentCaptor.forClass(SupplierScoreEventTrigger.class);
        verify(scorePendingService).mergeAndScheduleFire(triggerCaptor.capture());
        assertEquals(order.getId(), triggerCaptor.getValue().getSourceRefId());
        assertEquals("PO-SOURCE-10", triggerCaptor.getValue().getSourceRefNo(),
                "完全入库事件必须传采购单号，不能混入入库单号");
    }

    @Test
    void completingPurchaseOrderShouldRejectInboundBillWithoutConfirmedAt() {
        PurchaseOrder order = purchaseOrder(11L);
        PurchaseOrderItem orderItem = purchaseOrderItem(102L, 5L, 0L);
        InboundBill bill = new InboundBill()
                .setSourceType(SourceType.PURCHASE_ORDER.name())
                .setSourceId(order.getId());
        InboundBillItem inboundItem = new InboundBillItem()
                .setSourceItemId(orderItem.getId())
                .setCurrentQty(5L)
                .setProductCode("P000002")
                .setProductName("测试商品二");

        when(purchaseOrderMapper.selectById(order.getId())).thenReturn(order);
        when(purchaseOrderItemService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(orderItem));
        when(purchaseOrderItemService.updateBatchById(any())).thenReturn(true);

        assertThrows(BizException.class,
                () -> service.handleInboundConfirmation(bill, List.of(inboundItem)));

        verify(purchaseOrderMapper, never()).updateById(any(PurchaseOrder.class));
    }

    @Test
    void generatedInboundBillShouldCopyExpectedArrivalDateSnapshot() {
        LocalDate expectedArrivalDate = LocalDate.of(2026, 9, 28);
        PurchaseOrder order = purchaseOrder(12L)
                .setPurchaseNo("PO202609210001")
                .setSupplierName("测试供应商")
                .setWarehouseId(30L)
                .setWarehouseName("测试仓")
                .setExpectedArrivalDate(expectedArrivalDate);
        PurchaseOrderItem orderItem = purchaseOrderItem(103L, 8L, 3L);
        orderItem.setProductId(201L);
        orderItem.setProductCode("P000003");
        orderItem.setProductName("测试商品三");
        orderItem.setUnitName("件");
        orderItem.setQuantityPrecision(0);

        when(inboundBillMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        when(purchaseOrderItemService.list(any(LambdaQueryWrapper.class))).thenReturn(List.of(orderItem));
        when(billNoGenerator.nextNo(anyString(), any(ToLongFunction.class))).thenReturn("IB2026092100001");
        when(inboundBillMapper.insert(any(InboundBill.class))).thenAnswer(invocation -> {
            InboundBill inserted = invocation.getArgument(0);
            inserted.setId(301L);
            return 1;
        });

        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(1L);
        loginUser.setRealName("测试人员");
        try (MockedStatic<UserContext> userContext = org.mockito.Mockito.mockStatic(UserContext.class)) {
            userContext.when(UserContext::requireCurrentUser).thenReturn(loginUser);
            ReflectionTestUtils.invokeMethod(service, "generatePurchaseInboundBill", order);
        }

        ArgumentCaptor<InboundBill> captor = ArgumentCaptor.forClass(InboundBill.class);
        verify(inboundBillMapper).insert(captor.capture());
        assertEquals(expectedArrivalDate, captor.getValue().getExpectedArrivalDate());
        verify(inboundBillItemMapper).insert(any(InboundBillItem.class));
    }

    private PurchaseOrder purchaseOrder(Long id) {
        return new PurchaseOrder()
                .setId(id)
                .setSupplierId(20L)
                .setStatus(PurchaseOrderStatus.APPROVED.name());
    }

    private PurchaseOrderItem purchaseOrderItem(Long id, Long quantity, Long inboundQty) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setId(id);
        item.setPurchaseOrderId(10L);
        item.setQuantity(quantity);
        item.setInboundQty(inboundQty);
        return item;
    }
}
