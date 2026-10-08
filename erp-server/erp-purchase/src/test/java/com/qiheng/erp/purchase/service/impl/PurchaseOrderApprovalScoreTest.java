package com.qiheng.erp.purchase.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrderItem;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.IPurchaseOrderItemService;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.mapper.WarehouseMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 审核评分的批量冻结、锁顺序与失败中断测试；数据库真实回滚由集成测试验证。 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderApprovalScoreTest {
    @Mock private PurchaseOrderMapper orderMapper;
    @Mock private SupplierMapper supplierMapper;
    @Mock private WarehouseMapper warehouseMapper;
    @Mock private SupplierProductMapper productMapper;
    @Mock private IPurchaseOrderItemService itemService;
    @Mock private InboundBillMapper inboundMapper;
    @Mock private ApplicationEventPublisher publisher;

    private PurchaseOrderServiceImpl service;
    private PurchaseOrder order;
    private PurchaseOrderItem item;
    private SupplierProduct product;
    private List<PurchaseOrderItem> items;
    private List<SupplierProduct> products;

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderServiceImpl();
        ReflectionTestUtils.setField(service, "purchaseOrderMapper", orderMapper);
        ReflectionTestUtils.setField(service, "supplierMapper", supplierMapper);
        ReflectionTestUtils.setField(service, "warehouseMapper", warehouseMapper);
        ReflectionTestUtils.setField(service, "supplierProductMapper", productMapper);
        ReflectionTestUtils.setField(service, "purchaseOrderItemService", itemService);
        ReflectionTestUtils.setField(service, "inboundBillMapper", inboundMapper);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", publisher);
        order = new PurchaseOrder().setId(1L).setSupplierId(2L).setWarehouseId(3L)
                .setStatus("SUBMITTED").setVersion(1).setExpectedArrivalDate(java.time.LocalDate.now());
        item = new PurchaseOrderItem();
        item.setId(4L); item.setPurchaseOrderId(1L); item.setSupplierProductId(5L);
        item.setProductId(6L); item.setQuantity(100L); item.setQuantityPrecision(0);
        product = new SupplierProduct();
        product.setId(5L); product.setSupplierId(2L); product.setProductId(6L);
        product.setScoreStatus("READY"); product.setRecommendScore(8567);
        items = new ArrayList<>(List.of(item));
        products = new ArrayList<>(List.of(product));
    }

    /** 准备原有审核校验，不绕过实际服务方法。 */
    private void prepareValidation() {
        when(orderMapper.selectById(1L)).thenReturn(order);
        when(supplierMapper.selectById(2L)).thenReturn(new Supplier());
        when(warehouseMapper.selectById(3L)).thenReturn(new Warehouse());
        // 用例调整夹具列表即可，无须覆盖公共桩配置；保留 Mockito 严格校验。
        when(itemService.list(any(LambdaQueryWrapper.class))).thenAnswer(invocation -> items);
        when(productMapper.selectByIds(any())).thenAnswer(invocation -> products);
    }

    /** 准备快照持久化，已有待确认单用于隔离单号生成依赖。 */
    private void prepareSuccess() {
        prepareValidation();
        when(orderMapper.updateById(any(PurchaseOrder.class))).thenReturn(1);
        when(supplierMapper.lockByIdForUpdate(2L)).thenReturn(2L);
        when(itemService.updateBatchById(any())).thenReturn(true);
        when(inboundMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(1L);
    }

    private void approve() {
        LoginUser user = new LoginUser(); user.setUserId(7L); user.setRealName("审核测试人员");
        try (MockedStatic<UserContext> context = mockStatic(UserContext.class)) {
            context.when(UserContext::requireCurrentUser).thenReturn(user);
            service.approve(1L, 1);
        }
    }

    @Test
    void shouldFreezeLatestScoreAfterSupplierLock() {
        prepareSuccess();
        when(supplierMapper.lockByIdForUpdate(2L)).thenAnswer(invocation -> {
            product.setRecommendScore(9123);
            return 2L;
        });
        approve();
        assertEquals(9123, item.getSelectedSupplierScore());
        var sequence = inOrder(orderMapper, supplierMapper, productMapper, itemService);
        sequence.verify(orderMapper).updateById(any(PurchaseOrder.class));
        sequence.verify(supplierMapper).lockByIdForUpdate(2L);
        sequence.verify(productMapper).selectByIds(any());
        sequence.verify(itemService).updateBatchById(List.of(item));
    }

    @Test
    void shouldKeepValidZeroScore() {
        prepareSuccess(); product.setRecommendScore(0);
        approve(); assertEquals(0, item.getSelectedSupplierScore());
    }

    @Test
    void shouldKeepNullWhenReadyFlagHasNoActualScore() {
        prepareSuccess(); product.setRecommendScore(null);
        approve(); assertNull(item.getSelectedSupplierScore());
    }

    @Test
    void shouldFreezeDifferentProductsInOneBatch() {
        prepareSuccess();
        PurchaseOrderItem second = new PurchaseOrderItem();
        second.setId(8L); second.setPurchaseOrderId(1L); second.setSupplierProductId(9L);
        second.setProductId(10L); second.setQuantity(100L); second.setQuantityPrecision(0);
        SupplierProduct secondProduct = new SupplierProduct();
        secondProduct.setId(9L); secondProduct.setSupplierId(2L); secondProduct.setProductId(10L);
        secondProduct.setScoreStatus("READY"); secondProduct.setRecommendScore(0);
        items.add(second);
        products.add(secondProduct);
        approve();
        assertEquals(8567, item.getSelectedSupplierScore());
        assertEquals(0, second.getSelectedSupplierScore());
        verify(itemService).updateBatchById(List.of(item, second));
        verify(productMapper, times(2)).selectByIds(any());
    }

    @Test
    void shouldClearUnavailableScoreRatherThanWritingZero() {
        prepareSuccess(); product.setScoreStatus("NOT_READY"); item.setSelectedSupplierScore(9000);
        approve(); assertNull(item.getSelectedSupplierScore());
    }

    @Test
    void shouldStopBeforeInboundWhenSnapshotPersistenceFails() {
        prepareValidation();
        when(orderMapper.updateById(any(PurchaseOrder.class))).thenReturn(1);
        when(supplierMapper.lockByIdForUpdate(2L)).thenReturn(2L);
        when(itemService.updateBatchById(any())).thenReturn(false);
        assertThrows(BizException.class, this::approve);
        verifyNoInteractions(inboundMapper, publisher);
    }

    @Test
    void shouldRejectOwnershipChangedAfterValidation() {
        prepareValidation();
        when(orderMapper.updateById(any(PurchaseOrder.class))).thenReturn(1);
        when(supplierMapper.lockByIdForUpdate(2L)).thenAnswer(invocation -> {
            product.setSupplierId(99L); return 2L;
        });
        assertThrows(BizException.class, this::approve);
        verify(itemService, never()).updateBatchById(any());
        verifyNoInteractions(inboundMapper, publisher);
    }

    @Test
    void shouldNotReadScoresAfterOptimisticConflict() {
        prepareValidation(); when(orderMapper.updateById(any(PurchaseOrder.class))).thenReturn(0);
        assertThrows(BizException.class, this::approve);
        verify(supplierMapper, never()).lockByIdForUpdate(any());
        verify(itemService, never()).updateBatchById(any());
    }

    @Test
    void approvalTransactionShouldUseCommittedReadsAndRollbackOnAnyException() throws Exception {
        Transactional transaction = PurchaseOrderServiceImpl.class.getMethod("approve", Long.class, Integer.class)
                .getAnnotation(Transactional.class);
        assertEquals(Isolation.READ_COMMITTED, transaction.isolation());
        assertArrayEquals(new Class<?>[]{Exception.class}, transaction.rollbackFor());
    }
}
