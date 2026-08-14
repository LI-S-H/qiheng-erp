package com.qiheng.erp.returnorder.service.impl;

import com.qiheng.erp.returnorder.domain.port.ReturnSourceItem;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceOrder;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceProvider;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderItemVo;
import com.qiheng.erp.returnorder.mapper.ReturnOrderItemMapper;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 来源明细查询不启动 Spring 上下文，只验证四模块契约中最关键的可退数量派生规则。
 */
@ExtendWith(MockitoExtension.class)
class ReturnOrderServiceImplSourceItemsTest {

    @Mock
    private ReturnSourceProvider sourceProvider;
    @Mock
    private ReturnOrderMapper returnOrderMapper;
    @Mock
    private ReturnOrderItemMapper returnOrderItemMapper;
    @Mock
    private WarehouseStockMapper warehouseStockMapper;

    @BeforeEach
    void setUp() {
        when(returnOrderMapper.selectList(any())).thenReturn(List.of());
    }

    @Test
    void salesReturnDoesNotUseStockAndReturnsZeroStockAvailableQty() {
        ReturnOrderServiceImpl service = newService(ReturnType.SALES_RETURN);
        stubSource(1_001L, 2_001L, 1_000L);

        List<ReturnOrderItemVo> items = service.listSourceItems(ReturnType.SALES_RETURN, 1_001L);

        assertThat(items).singleElement().satisfies(item -> {
            assertDecimal(item.getSourceFulfilledQty(), "10.00");
            assertDecimal(item.getStockAvailableQty(), "0.00");
            assertDecimal(item.getOccupiedQty(), "0.00");
            assertDecimal(item.getAvailableReturnQty(), "10.00");
        });
    }

    @Test
    void purchaseReturnIsLimitedByCurrentAvailableStock() {
        ReturnOrderServiceImpl service = newService(ReturnType.PURCHASE_RETURN);
        stubSource(1_002L, 2_002L, 800L);
        when(warehouseStockMapper.selectList(any())).thenReturn(List.of(new WarehouseStock()
                .setWarehouseId(501L)
                .setProductId(101L)
                .setStockQty(1_000L)
                .setLockedQty(300L)));

        List<ReturnOrderItemVo> items = service.listSourceItems(ReturnType.PURCHASE_RETURN, 1_002L);

        assertThat(items).singleElement().satisfies(item -> {
            assertDecimal(item.getSourceFulfilledQty(), "8.00");
            assertDecimal(item.getStockAvailableQty(), "7.00");
            assertDecimal(item.getAvailableReturnQty(), "7.00");
        });
    }

    @Test
    void fulfilledPurchaseItemWithNoAvailableStockIsReturnedButCannotBeSelected() {
        ReturnOrderServiceImpl service = newService(ReturnType.PURCHASE_RETURN);
        stubSource(1_003L, 2_003L, 500L);
        when(warehouseStockMapper.selectList(any())).thenReturn(List.of(new WarehouseStock()
                .setWarehouseId(501L)
                .setProductId(101L)
                .setStockQty(500L)
                .setLockedQty(500L)));

        List<ReturnOrderItemVo> items = service.listSourceItems(ReturnType.PURCHASE_RETURN, 1_003L);

        assertThat(items).singleElement().satisfies(item -> {
            assertDecimal(item.getStockAvailableQty(), "0.00");
            assertDecimal(item.getAvailableReturnQty(), "0.00");
        });
    }

    private ReturnOrderServiceImpl newService(ReturnType returnType) {
        ReturnOrderServiceImpl service = new ReturnOrderServiceImpl();
        ReflectionTestUtils.setField(service, "sourceProviders", List.of(sourceProvider));
        ReflectionTestUtils.setField(service, "returnOrderMapper", returnOrderMapper);
        ReflectionTestUtils.setField(service, "returnOrderItemMapper", returnOrderItemMapper);
        ReflectionTestUtils.setField(service, "warehouseStockMapper", warehouseStockMapper);
        when(sourceProvider.supportsType()).thenReturn(returnType);
        return service;
    }

    private void stubSource(Long sourceOrderId, Long sourceItemId, Long fulfilledQty) {
        ReturnSourceOrder sourceOrder = new ReturnSourceOrder(sourceOrderId, "SRC-" + sourceOrderId,
                301L, "PARTY-301", "往来单位", 501L, "主仓");
        ReturnSourceItem sourceItem = new ReturnSourceItem(sourceItemId, 101L, "P-101", "测试产品",
                "件", 2, fulfilledQty, 1_234L);
        when(sourceProvider.getSourceOrder(sourceOrderId)).thenReturn(sourceOrder);
        when(sourceProvider.listSourceItems(List.of(sourceOrderId))).thenReturn(Map.of(sourceOrderId, List.of(sourceItem)));
    }

    private void assertDecimal(BigDecimal actual, String expected) {
        assertThat(actual).isEqualByComparingTo(expected);
    }
}
