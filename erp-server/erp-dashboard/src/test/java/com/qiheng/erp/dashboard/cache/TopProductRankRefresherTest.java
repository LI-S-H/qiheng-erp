package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.constant.SystemExceptionConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证 TOP 商品排行重建任务的上报语义：FAILED 报 MEDIUM、未捕获异常报 HIGH、成功与抢占零上报。
 *
 * @author Li
 * @since 2026-10-07
 */
class TopProductRankRefresherTest {

    private TopProductRankCache rankCache;
    private SystemExceptionMqPublisher publisher;
    private TopProductRankRefresher refresher;

    @BeforeEach
    void setUp() {
        rankCache = mock(TopProductRankCache.class);
        publisher = mock(SystemExceptionMqPublisher.class);
        refresher = new TopProductRankRefresher(null, null, rankCache, publisher);
    }

    @Test
    void failedRebuildReportsMediumSeverity() {
        when(rankCache.rebuild(anySupplier())).thenReturn(TopProductRankCache.RebuildOutcome.FAILED);

        refresher.refresh();

        verify(publisher).publishJobFailure(
                eq("dashboard-top-product-rebuild"),
                contains("重建失败"),
                anyString(),
                eq(SystemExceptionConstants.SEVERITY_MEDIUM));
    }

    @Test
    void unexpectedExceptionIsReportedHighAndNotRethrown() {
        // SQL/Redis 环境级故障会穿透 rebuild 抛出，任务必须吞掉并上报 HIGH，不能让调度器静默吞掉
        doThrow(new RuntimeException("Redis 不可用")).when(rankCache).rebuild(anySupplier());

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(refresher::refresh);

        verify(publisher).publishJobFailure(
                eq("dashboard-top-product-rebuild"),
                contains("抛出异常"),
                anyString(),
                eq(SystemExceptionConstants.SEVERITY_HIGH));
    }

    @Test
    void successAndSkippedOutcomesReportNothing() {
        when(rankCache.rebuild(anySupplier()))
                .thenReturn(TopProductRankCache.RebuildOutcome.SUCCESS)
                .thenReturn(TopProductRankCache.RebuildOutcome.SKIPPED_LOCK_BUSY);

        refresher.refresh();
        refresher.refresh();

        // 成功与多实例正常抢占都不是故障，零上报
        verifyNoInteractions(publisher);
    }

    private Supplier<List<TopProductRankCache.RankEntry>> anySupplier() {
        return org.mockito.ArgumentMatchers.any();
    }
}
