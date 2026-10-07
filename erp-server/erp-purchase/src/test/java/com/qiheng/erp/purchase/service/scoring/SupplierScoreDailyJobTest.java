package com.qiheng.erp.purchase.service.scoring;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.job.SupplierScoreScheduledJob;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import java.util.List;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplierScoreDailyJobTest {
    @Test
    void expiredQuotesShouldRecalculateEachSupplierOnlyOnce() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), SupplierProduct.class);
        SupplierMapper supplierMapper = mock(SupplierMapper.class);
        SupplierProductMapper productMapper = mock(SupplierProductMapper.class);
        SupplierScoreRecalculateService recalc = mock(SupplierScoreRecalculateService.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        SupplierProduct first = new SupplierProduct().setId(1L).setSupplierId(10L)
                .setQuoteValidUntil(LocalDate.now(ZoneId.of("Asia/Shanghai")).minusDays(1));
        SupplierProduct second = new SupplierProduct().setId(2L).setSupplierId(10L)
                .setQuoteValidUntil(first.getQuoteValidUntil());
        when(productMapper.selectList(any())).thenReturn(List.of(first, second));
        new SupplierScoreScheduledJob(recalc, supplierMapper, productMapper, redisson).scanExpiredQuotes();
        verify(recalc).recalcPricesForSupplier(10L);
        verify(lock).unlock();
    }

    @Test
    void oneSupplierFailureDoesNotStopLaterSupplier() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"), Supplier.class);
        SupplierMapper mapper = mock(SupplierMapper.class);
        SupplierScoreRecalculateService recalc = mock(SupplierScoreRecalculateService.class);
        RedissonClient redisson = mock(RedissonClient.class); RLock lock = mock(RLock.class);
        when(redisson.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        Supplier first = new Supplier(); first.setId(1L); Supplier second = new Supplier(); second.setId(2L);
        when(mapper.selectList(any())).thenReturn(List.of(first, second));
        when(recalc.recalcFactsForSupplier(argThat(c -> c.getSupplierId().equals(1L)), any())).thenThrow(new IllegalStateException("test"));
        new SupplierScoreScheduledJob(recalc, mapper, mock(SupplierProductMapper.class), redisson).dailyReconciliation();
        verify(recalc, times(2)).recalcFactsForSupplier(any(), any());
        verify(redisson, times(2)).getLock(startsWith("supplier:score:lock:"));
        verify(lock, times(3)).unlock();
    }
}
