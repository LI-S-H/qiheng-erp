package com.qiheng.erp.dashboard.job;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qiheng.erp.dashboard.cache.PrevValueCache;
import com.qiheng.erp.dashboard.cache.model.PendingOrderSnapshot;
import com.qiheng.erp.dashboard.loader.DashboardStockAlertLoader;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.mapper.OutboundBillMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DashboardDailySnapshotJobTest {

    @Test
    void shouldPersistPendingOrderBreakdownSnapshot() {
        PrevValueCache prevValueCache = mock(PrevValueCache.class);
        DashboardStockAlertLoader stockAlertLoader = mock(DashboardStockAlertLoader.class);
        PurchaseOrderMapper purchaseOrderMapper = mock(PurchaseOrderMapper.class);
        SalesOrderMapper salesOrderMapper = mock(SalesOrderMapper.class);
        InboundBillMapper inboundBillMapper = mock(InboundBillMapper.class);
        OutboundBillMapper outboundBillMapper = mock(OutboundBillMapper.class);
        ReturnOrderMapper returnOrderMapper = mock(ReturnOrderMapper.class);

        when(purchaseOrderMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        when(salesOrderMapper.selectCount(any(Wrapper.class))).thenReturn(3L);
        when(inboundBillMapper.selectCount(any(Wrapper.class))).thenReturn(5L);
        when(outboundBillMapper.selectCount(any(Wrapper.class))).thenReturn(7L);
        when(stockAlertLoader.countRiskSkus()).thenReturn(11);
        when(returnOrderMapper.selectCount(any(Wrapper.class))).thenReturn(4L, 6L);

        new DashboardDailySnapshotJob(prevValueCache, stockAlertLoader, purchaseOrderMapper,
                salesOrderMapper, inboundBillMapper, outboundBillMapper, returnOrderMapper).snapshot();

        verify(prevValueCache).putPendingOrderSnapshot(LocalDate.now(),
                new PendingOrderSnapshot(2L, 4L, 3L, 6L, 5L, 7L));
        verify(prevValueCache).putDailySnapshot(LocalDate.now(),
                PrevValueCache.DailySnapshotType.STOCK_RISK_COUNT, BigDecimal.valueOf(11L));
    }
}