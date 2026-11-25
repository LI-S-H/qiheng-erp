package com.qiheng.erp.returnorder.service.impl;

import com.qiheng.erp.returnorder.domain.port.ReturnSourceItem;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceOrder;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceProvider;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.domain.vo.ReturnableSourceDetailVo;
import com.qiheng.erp.returnorder.mapper.ReturnOrderItemMapper;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 可退来源聚合查询：来源单表头与可退明细必须来自同一套计算口径。
 *
 * @author Li
 * @since 2026-11-24
 */
@ExtendWith(MockitoExtension.class)
class ReturnOrderServiceImplReturnableSourceTest {

    @Mock
    private ReturnSourceProvider sourceProvider;
    @Mock
    private ReturnOrderMapper returnOrderMapper;
    @Mock
    private ReturnOrderItemMapper returnOrderItemMapper;
    @Mock
    private WarehouseStockMapper warehouseStockMapper;

    @Test
    void salesReturnSourceExposesHeaderAndAvailableItems() {
        ReturnOrderServiceImpl service = newService(ReturnType.SALES_RETURN);
        stubSource(1_001L, "SO2026112300001", "OUTBOUND_DONE", 2_001L, 1_000L);
        when(returnOrderMapper.selectList(any())).thenReturn(List.of());

        ReturnableSourceDetailVo source = service.getReturnableSource(ReturnType.SALES_RETURN, 1_001L);

        assertThat(source.getSourceOrderNo()).isEqualTo("SO2026112300001");
        assertThat(source.getSourceOrderStatus()).isEqualTo("OUTBOUND_DONE");
        assertThat(source.getWarehouseId()).isEqualTo(501L);
        assertThat(source.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getSourceOrderItemId()).isEqualTo(2_001L);
            assertThat(item.getSourceFulfilledQty()).isEqualByComparingTo("10.00");
            assertThat(item.getStockAvailableQty()).isEqualByComparingTo("0.00");
            assertThat(item.getAvailableReturnQty()).isEqualByComparingTo("10.00");
        });
    }

    @Test
    void purchaseReturnSourceCapsAvailableByWarehouseStock() {
        ReturnOrderServiceImpl service = newService(ReturnType.PURCHASE_RETURN);
        stubSource(1_002L, "PO2026112300001", "PARTIAL_INBOUND", 2_002L, 800L);
        when(returnOrderMapper.selectList(any())).thenReturn(List.of());
        when(warehouseStockMapper.selectList(any())).thenReturn(List.of(new WarehouseStock()
                .setWarehouseId(501L)
                .setProductId(101L)
                .setStockQty(1_000L)
                .setLockedQty(300L)));

        ReturnableSourceDetailVo source = service.getReturnableSource(ReturnType.PURCHASE_RETURN, 1_002L);

        assertThat(source.getSourceOrderStatus()).isEqualTo("PARTIAL_INBOUND");
        assertThat(source.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getStockAvailableQty()).isEqualByComparingTo("7.00");
            assertThat(item.getAvailableReturnQty()).isEqualByComparingTo("7.00");
        });
    }

    @Test
    void occupancyUsesRequestedQtyForSubmittedAndApprovedQtyForApproved() {
        ReturnOrderServiceImpl service = newService(ReturnType.SALES_RETURN);
        stubSource(1_003L, "SO2026112300003", "OUTBOUND_DONE", 2_003L, 1_000L);
        when(returnOrderMapper.selectList(any())).thenReturn(List.of(
                new com.qiheng.erp.returnorder.domain.entity.ReturnOrder().setId(9_001L).setStatus("SUBMITTED"),
                new com.qiheng.erp.returnorder.domain.entity.ReturnOrder().setId(9_002L).setStatus("APPROVED")));
        when(returnOrderItemMapper.selectList(any())).thenReturn(List.of(
                new com.qiheng.erp.returnorder.domain.entity.ReturnOrderItem()
                        .setReturnOrderId(9_001L).setSourceOrderItemId(2_003L)
                        .setRequestedQty(300L).setApprovedQty(0L),
                new com.qiheng.erp.returnorder.domain.entity.ReturnOrderItem()
                        .setReturnOrderId(9_002L).setSourceOrderItemId(2_003L)
                        .setRequestedQty(600L).setApprovedQty(400L)));

        ReturnableSourceDetailVo source = service.getReturnableSource(ReturnType.SALES_RETURN, 1_003L);

        assertThat(source.getItems()).singleElement().satisfies(item -> {
            // 待审核按申请量 3.00，已审核按审核量 4.00，合计占用 7.00。
            assertThat(item.getOccupiedQty()).isEqualByComparingTo("7.00");
            assertThat(item.getAvailableReturnQty()).isEqualByComparingTo("3.00");
        });
    }

    @Test
    void getReturnableSourceByNoResolvesExactOrderOnly() {
        ReturnOrderServiceImpl service = newService(ReturnType.SALES_RETURN);
        stubSource(1_004L, "SO2026112300004", "OUTBOUND_DONE", 2_004L, 500L);
        when(returnOrderMapper.selectList(any())).thenReturn(List.of());
        when(sourceProvider.searchSourceOrders(eq("SO2026112300004"), anyInt())).thenReturn(List.of(
                sourceOrder(1_999L, "SO20261123000040", "OUTBOUND_DONE"),
                sourceOrder(1_004L, "SO2026112300004", "OUTBOUND_DONE")));

        ReturnableSourceDetailVo source = service.getReturnableSourceByNo(ReturnType.SALES_RETURN, "SO2026112300004");

        assertThat(source.getSourceOrderId()).isEqualTo(1_004L);
        assertThat(source.getSourceOrderNo()).isEqualTo("SO2026112300004");
    }

    /** 构造只绑定来源提供者与可退计算所需依赖的服务实例。 */
    private ReturnOrderServiceImpl newService(ReturnType returnType) {
        ReturnOrderServiceImpl service = new ReturnOrderServiceImpl();
        ReflectionTestUtils.setField(service, "sourceProviders", List.of(sourceProvider));
        ReflectionTestUtils.setField(service, "returnOrderMapper", returnOrderMapper);
        ReflectionTestUtils.setField(service, "returnOrderItemMapper", returnOrderItemMapper);
        ReflectionTestUtils.setField(service, "warehouseStockMapper", warehouseStockMapper);
        when(sourceProvider.supportsType()).thenReturn(returnType);
        return service;
    }

    private void stubSource(Long sourceOrderId, String sourceOrderNo, String status,
                            Long sourceItemId, Long fulfilledQty) {
        ReturnSourceOrder sourceOrder = sourceOrder(sourceOrderId, sourceOrderNo, status);
        ReturnSourceItem sourceItem = new ReturnSourceItem(sourceItemId, 101L, "P-101", "测试产品",
                "件", 2, fulfilledQty, 1_234L);
        when(sourceProvider.getSourceOrder(sourceOrderId)).thenReturn(sourceOrder);
        when(sourceProvider.listSourceItems(List.of(sourceOrderId))).thenReturn(Map.of(sourceOrderId, List.of(sourceItem)));
    }

    private ReturnSourceOrder sourceOrder(Long sourceOrderId, String sourceOrderNo, String status) {
        return new ReturnSourceOrder(sourceOrderId, sourceOrderNo, 301L, "PARTY-301", "往来单位",
                501L, "主仓", null, status);
    }
}