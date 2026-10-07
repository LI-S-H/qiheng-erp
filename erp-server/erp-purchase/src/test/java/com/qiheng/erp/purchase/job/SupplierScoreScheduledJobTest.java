package com.qiheng.erp.purchase.job;

import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreRecalcContext;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 验证每日入口不再注入特殊编号、批次统一由日志入口生成，以及失败汇总上报不刷屏。 */
class SupplierScoreScheduledJobTest {

    private SupplierProduct expiredProduct(Long id, Long supplierId) {
        return new SupplierProduct().setId(id).setSupplierId(supplierId);
    }

    @Test
    void dailyEntryLeavesBatchUnsetAndLocksSupplierBeforeRecalculation() throws Exception {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), "daily-batch-test"), Supplier.class);
        SupplierMapper suppliers = mock(SupplierMapper.class);
        SupplierScoreRecalculateService service = mock(SupplierScoreRecalculateService.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock dailyLock = mock(RLock.class);
        RLock supplierLock = mock(RLock.class);
        SystemExceptionMqPublisher publisher = mock(SystemExceptionMqPublisher.class);
        when(redisson.getLock("supplier:score:daily:lock")).thenReturn(dailyLock);
        when(redisson.getLock("supplier:score:lock:7")).thenReturn(supplierLock);
        when(dailyLock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(supplierLock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(dailyLock.isHeldByCurrentThread()).thenReturn(true);
        when(supplierLock.isHeldByCurrentThread()).thenReturn(true);
        when(suppliers.selectList(any())).thenReturn(List.of(new Supplier().setId(7L)));
        new SupplierScoreScheduledJob(service, suppliers, mock(SupplierProductMapper.class), publisher, redisson)
                .dailyReconciliation();
        ArgumentCaptor<ScoreRecalcContext> context = ArgumentCaptor.forClass(ScoreRecalcContext.class);
        var order = inOrder(supplierLock, service);
        order.verify(supplierLock).tryLock(5, TimeUnit.SECONDS);
        order.verify(service).recalcFactsForSupplier(context.capture(), any());
        order.verify(supplierLock).isHeldByCurrentThread();
        order.verify(supplierLock).unlock();
        assertEquals(TriggerType.DAILY_TRIGGER, context.getValue().getTriggerType());
        assertEquals(7L, context.getValue().getSupplierId());
        assertNull(context.getValue().getBatchNo());
        assertEquals("SCHEDULED", context.getValue().getExecutionMode());
        verify(dailyLock).unlock();
        // 全部成功时必须零上报，避免工作台待办被无故障噪音污染
        verifyNoInteractions(publisher);
    }

    @Test
    void scanExpiredQuotesAggregatesFailuresIntoSingleLowReport() throws Exception {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), "quote-batch-test"), SupplierProduct.class);
        SupplierProductMapper products = mock(SupplierProductMapper.class);
        SupplierScoreRecalculateService service = mock(SupplierScoreRecalculateService.class);
        RedissonClient redisson = mock(RedissonClient.class);
        SystemExceptionMqPublisher publisher = mock(SystemExceptionMqPublisher.class);
        RLock lock7 = mock(RLock.class);
        RLock lock8 = mock(RLock.class);
        when(redisson.getLock("supplier:score:lock:7")).thenReturn(lock7);
        when(redisson.getLock("supplier:score:lock:8")).thenReturn(lock8);
        when(lock7.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock8.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock7.isHeldByCurrentThread()).thenReturn(true);
        when(lock8.isHeldByCurrentThread()).thenReturn(true);
        // 两个产品分别属于供应商 7 与 8，供应商 8 重算失败
        when(products.selectList(any())).thenReturn(List.of(expiredProduct(1L, 7L), expiredProduct(2L, 8L)));
        doThrow(new RuntimeException("评分事实查询失败")).when(service).recalcPricesForSupplier(8L);

        new SupplierScoreScheduledJob(service, mock(SupplierMapper.class), products, publisher, redisson)
                .scanExpiredQuotes();

        // per-supplier 失败必须汇总成一条 LOW 上报，绝不逐家发送
        verify(publisher, times(1)).publishJobFailure(
                eq("supplier-score-quote-expired"),
                contains("失败 1 家"),
                anyString(),
                eq("LOW"));
    }

    @Test
    void dailyReconciliationAggregatesFailuresIntoSingleLowReport() throws Exception {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), "daily-fail-test"), Supplier.class);
        SupplierMapper suppliers = mock(SupplierMapper.class);
        SupplierScoreRecalculateService service = mock(SupplierScoreRecalculateService.class);
        RedissonClient redisson = mock(RedissonClient.class);
        SystemExceptionMqPublisher publisher = mock(SystemExceptionMqPublisher.class);
        RLock dailyLock = mock(RLock.class);
        RLock lock7 = mock(RLock.class);
        RLock lock8 = mock(RLock.class);
        when(redisson.getLock("supplier:score:daily:lock")).thenReturn(dailyLock);
        when(redisson.getLock("supplier:score:lock:7")).thenReturn(lock7);
        when(redisson.getLock("supplier:score:lock:8")).thenReturn(lock8);
        when(dailyLock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock7.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(lock8.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(dailyLock.isHeldByCurrentThread()).thenReturn(true);
        when(lock7.isHeldByCurrentThread()).thenReturn(true);
        when(lock8.isHeldByCurrentThread()).thenReturn(true);
        when(suppliers.selectList(any())).thenReturn(List.of(new Supplier().setId(7L), new Supplier().setId(8L)));
        doThrow(new RuntimeException("DB 闪断")).when(service).recalcFactsForSupplier(any(), any());

        new SupplierScoreScheduledJob(service, suppliers, mock(SupplierProductMapper.class), publisher, redisson)
                .dailyReconciliation();

        // 单家失败有次日自愈兜底，汇总一条 LOW；整体未崩不触发 HIGH
        verify(publisher, times(1)).publishJobFailure(
                eq("supplier-score-daily-reconciliation"),
                contains("失败 2 家"),
                anyString(),
                eq("LOW"));
    }
}
