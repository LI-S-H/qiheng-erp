package com.qiheng.erp.dashboard.job;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.qiheng.erp.dashboard.cache.DashboardPrevValueCache;
import com.qiheng.erp.dashboard.cache.model.PendingOrderSnapshot;
import com.qiheng.erp.dashboard.loader.DashboardStockAlertLoader;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
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
        DashboardPrevValueCache prevValueCache = mock(DashboardPrevValueCache.class);
        DashboardStockAlertLoader stockAlertLoader = mock(DashboardStockAlertLoader.class);
        PurchaseOrderMapper purchaseOrderMapper = mock(PurchaseOrderMapper.class);
        SalesOrderMapper salesOrderMapper = mock(SalesOrderMapper.class);
        InboundBillMapper inboundBillMapper = mock(InboundBillMapper.class);
        OutboundBillMapper outboundBillMapper = mock(OutboundBillMapper.class);

        when(purchaseOrderMapper.selectCount(any(Wrapper.class))).thenReturn(2L);
        when(salesOrderMapper.selectCount(any(Wrapper.class))).thenReturn(3L);
        when(inboundBillMapper.selectCount(any(Wrapper.class))).thenReturn(5L);
        when(outboundBillMapper.selectCount(any(Wrapper.class))).thenReturn(7L);
        when(stockAlertLoader.countRiskSkus()).thenReturn(11);

        new DashboardDailySnapshotJob(prevValueCache, stockAlertLoader, purchaseOrderMapper,
                salesOrderMapper, inboundBillMapper, outboundBillMapper).snapshot();

        verify(prevValueCache).putPendingOrderSnapshot(LocalDate.now(),
                new PendingOrderSnapshot(2L, 3L, 5L, 7L));
        verify(prevValueCache).putDailySnapshot(LocalDate.now(),
                DashboardPrevValueCache.DailySnapshotType.STOCK_RISK_COUNT, BigDecimal.valueOf(11L));
    }
}