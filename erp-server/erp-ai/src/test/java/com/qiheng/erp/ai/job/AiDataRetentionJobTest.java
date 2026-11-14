package com.qiheng.erp.ai.job;

import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 核实过期数据清理的分批行为。
 *
 * <p>每批固定条数，某批不足一批即视为已清空；单批之外不再空转。
 * 两类数据各自独立分批，互不影响。</p>
 */
class AiDataRetentionJobTest {

    /** 数据集连续两批（满批 + 未满批）后停止，结果类型一批即清空。 */
    @Test
    void stopsAfterPartialBatch() {
        var datasets = mock(DatasetManager.class);
        var results = mock(AnalysisResultStore.class);
        when(datasets.purgeExpired(200)).thenReturn(200, 50);
        when(results.purgeExpired(200)).thenReturn(0);

        new AiDataRetentionJob(datasets, results).purgeExpired();

        verify(datasets, times(2)).purgeExpired(200);
        verify(results, times(1)).purgeExpired(200);
    }
}