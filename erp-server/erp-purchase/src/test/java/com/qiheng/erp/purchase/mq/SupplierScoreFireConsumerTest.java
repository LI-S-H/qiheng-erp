package com.qiheng.erp.purchase.mq;

import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreRecalcContext;
import com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreFireMessage;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import com.qiheng.erp.purchase.service.support.PendingRedisSupport;
import com.qiheng.erp.purchase.service.support.ScoreRecalcPendingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证消息重投与合并批次不会漏算。 */
@ExtendWith(MockitoExtension.class)
class SupplierScoreFireConsumerTest {
    @Mock private SupplierScoreRecalculateService recalculateService;
    @Mock private PendingRedisSupport pendingRedisSupport;
    @Mock private RedissonClient redissonClient;
    @Mock private RLock lock;

    private SupplierScoreFireConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new SupplierScoreFireConsumer(recalculateService, pendingRedisSupport, redissonClient);
        when(redissonClient.getLock(any(String.class))).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
    }

    @Test
    void deletePendingOnlyAfterSuccessfulRecalculation() {
        when(pendingRedisSupport.findMatchingSnapshot(7L, "batch-1"))
                .thenReturn(snapshot(101L));

        consumer.onMessage(message("batch-1"));

        InOrder order = inOrder(lock, pendingRedisSupport, recalculateService);
        order.verify(lock).lock();
        order.verify(pendingRedisSupport).findMatchingSnapshot(7L, "batch-1");
        order.verify(recalculateService).recalcForSupplier(any());
        order.verify(pendingRedisSupport).compareAndDelete(7L, "batch-1");
        order.verify(lock).unlock();
        ArgumentCaptor<ScoreRecalcContext> context = ArgumentCaptor.forClass(ScoreRecalcContext.class);
        verify(recalculateService).recalcForSupplier(context.capture());
        assertEquals("101", context.getValue().getRelatedSources().getFirst().getBusinessId());
        assertEquals("PO-101", context.getValue().getRelatedSources().getFirst().getBusinessNo());
        assertEquals(List.of(101L), context.getValue().getCompletedOrderIds());
        assertEquals("batch-1", context.getValue().getBatchNo(), "消费必须沿用消息编号，不另行生成日志批次");
    }

    @Test
    void failedRecalculationPreservesPendingForRetry() {
        when(pendingRedisSupport.findMatchingSnapshot(7L, "batch-1"))
                .thenReturn(snapshot(101L));
        when(recalculateService.recalcForSupplier(any())).thenThrow(new IllegalStateException("数据库故障"));

        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message("batch-1")));

        verify(pendingRedisSupport, never()).compareAndDelete(any(), any());
        verify(lock).unlock();
    }

    @Test
    void mergedOrdersDoNotChooseSingleOrderIncrement() {
        ScoreRecalcPendingService.PendingSnapshot snapshot = snapshot(101L);
        ScoreRecalcPendingService.PendingSnapshot.SourceRef second = new ScoreRecalcPendingService.PendingSnapshot.SourceRef();
        second.setSourceRefId(102L);
        second.setSourceRefNo("PO-102");
        snapshot.getSourceRefs().add(second);
        when(pendingRedisSupport.findMatchingSnapshot(7L, "batch-1")).thenReturn(snapshot);

        consumer.onMessage(message("batch-1"));

        ArgumentCaptor<ScoreRecalcContext> context = ArgumentCaptor.forClass(ScoreRecalcContext.class);
        verify(recalculateService).recalcForSupplier(context.capture());
        assertEquals(2, context.getValue().getRelatedSources().size());
        assertEquals(List.of(101L, 102L), context.getValue().getCompletedOrderIds());
    }

    @Test
    void duplicateSourcesArePassedOnceAndKeepSingleOrderSource() {
        var pending = snapshot(101L);
        pending.getSourceRefs().add(pending.getSourceRefs().getFirst());
        when(pendingRedisSupport.findMatchingSnapshot(7L, "batch-1")).thenReturn(pending);
        consumer.onMessage(message("batch-1"));
        ArgumentCaptor<ScoreRecalcContext> context = ArgumentCaptor.forClass(ScoreRecalcContext.class);
        verify(recalculateService).recalcForSupplier(context.capture());
        assertEquals(List.of(101L), context.getValue().getCompletedOrderIds());
        assertEquals(1, context.getValue().getRelatedSources().size());
    }

    @Test
    void cleanupFailureRetriesTheSameBatchWithoutDroppingPending() {
        when(pendingRedisSupport.findMatchingSnapshot(7L, "batch-1")).thenReturn(snapshot(101L));
        when(pendingRedisSupport.compareAndDelete(7L, "batch-1")).thenThrow(new IllegalStateException("Redis 清理故障"));
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message("batch-1")));
        verify(recalculateService).recalcForSupplier(any());
        verify(lock).unlock();
    }

    @Test
    void moreThanTenOrdersRetainEverySourceAndReasonUsesDistinctOrderCount() {
        var pending = snapshot(101L);
        for (long id = 102; id <= 112; id++) {
            var source = new ScoreRecalcPendingService.PendingSnapshot.SourceRef();
            source.setSourceRefId(id); source.setSourceRefNo("PO-" + id);
            pending.getSourceRefs().add(source);
        }
        pending.getSourceRefs().add(pending.getSourceRefs().getFirst());
        when(pendingRedisSupport.findMatchingSnapshot(7L, "batch-1")).thenReturn(pending);
        consumer.onMessage(message("batch-1"));
        var context = ArgumentCaptor.forClass(ScoreRecalcContext.class);
        verify(recalculateService).recalcForSupplier(context.capture());
        assertEquals(12, context.getValue().getCompletedOrderIds().size());
        assertEquals(12, context.getValue().getRelatedSources().size());
        assertEquals("完全入库合并重算，共 12 张采购单", context.getValue().getMergedReason());
        assertEquals("PO-112", context.getValue().getRelatedSources().getLast().getBusinessNo());
    }

    private SupplierScoreFireMessage message(String token) {
        SupplierScoreFireMessage message = new SupplierScoreFireMessage();
        message.setSupplierId(7L);
        message.setBatchNo(token);
        return message;
    }

    private ScoreRecalcPendingService.PendingSnapshot snapshot(Long purchaseOrderId) {
        ScoreRecalcPendingService.PendingSnapshot.SourceRef ref = new ScoreRecalcPendingService.PendingSnapshot.SourceRef();
        ref.setSourceRefId(purchaseOrderId);
        ref.setSourceRefNo("PO-" + purchaseOrderId);
        ScoreRecalcPendingService.PendingSnapshot snapshot = new ScoreRecalcPendingService.PendingSnapshot();
        snapshot.setBatchNo("batch-1");
        snapshot.setSourceRefs(new java.util.ArrayList<>(List.of(ref)));
        return snapshot;
    }
}
