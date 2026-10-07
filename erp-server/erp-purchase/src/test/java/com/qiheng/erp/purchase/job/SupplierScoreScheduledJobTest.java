package com.qiheng.erp.purchase.job;

import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
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

/** 验证每日入口不再注入特殊编号，批次统一交由日志入口生成，且仍持外层供应商锁。 */
class SupplierScoreScheduledJobTest {
    @Test
    void dailyEntryLeavesBatchUnsetAndLocksSupplierBeforeRecalculation() throws Exception {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(new org.apache.ibatis.session.Configuration(), "daily-batch-test"), Supplier.class);
        SupplierMapper suppliers = mock(SupplierMapper.class);
        SupplierScoreRecalculateService service = mock(SupplierScoreRecalculateService.class);
        RedissonClient redisson = mock(RedissonClient.class);
        RLock dailyLock = mock(RLock.class);
        RLock supplierLock = mock(RLock.class);
        when(redisson.getLock("supplier:score:daily:lock")).thenReturn(dailyLock);
        when(redisson.getLock("supplier:score:lock:7")).thenReturn(supplierLock);
        when(dailyLock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(supplierLock.tryLock(5, TimeUnit.SECONDS)).thenReturn(true);
        when(dailyLock.isHeldByCurrentThread()).thenReturn(true);
        when(supplierLock.isHeldByCurrentThread()).thenReturn(true);
        when(suppliers.selectList(any())).thenReturn(List.of(new Supplier().setId(7L)));
        new SupplierScoreScheduledJob(service, suppliers, mock(SupplierProductMapper.class), redisson).dailyReconciliation();
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
    }
}
