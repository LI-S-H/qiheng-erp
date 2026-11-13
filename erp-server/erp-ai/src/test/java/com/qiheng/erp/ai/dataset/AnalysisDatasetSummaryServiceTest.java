package com.qiheng.erp.ai.dataset;

import com.qiheng.erp.ai.context.AgentToolContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisDatasetSummaryServiceTest {
    private final DatasetManager datasets = mock(DatasetManager.class);
    private final AnalysisDatasetSummaryService service = new AnalysisDatasetSummaryService(datasets);
    private final AgentToolContext identity = new AgentToolContext(10, 20);

    /** 仅对当前身份读取前两行，完整性与预览范围分别保留。 */
    @Test
    void returnsTwoRowsAndCompleteness() {
        var record = new DatasetRecord("ds-a", "run", 10, 20, "库存", List.of(), 3, false,
                "READY", null, null, "ROW_LIMIT");
        when(datasets.listReady(10, 20)).thenReturn(List.of(record));
        when(datasets.readPage("ds-a", 10, 20, 0, 2)).thenReturn(
                new DatasetPage(List.of(), List.of(List.of(1), List.of(2)), 3, 2, true));
        var result = service.listForAnalysis(identity);
        assertEquals(0, result.unavailableCount());
        var item = result.datasets().getFirst();
        assertFalse(item.completed());
        assertEquals(2, item.preview().returnedRows());
        assertTrue(item.preview().hasMore());
        assertEquals("SUCCESS", item.preview().status());
        verify(datasets).readPage("ds-a", 10, 20, 0, 2);
    }

    /** 撤权或读取失败时不暴露原数据集信息。 */
    @Test
    void omitsUnavailableDatasets() {
        var record = new DatasetRecord("ds-a", "run", 10, 20, "库存", List.of(), 1, true,
                "READY", null, null);
        when(datasets.listReady(10, 20)).thenReturn(List.of(record));
        when(datasets.readPage("ds-a", 10, 20, 0, 2)).thenThrow(new IllegalArgumentException("无权访问"));
        var result = service.listForAnalysis(identity);
        assertTrue(result.datasets().isEmpty());
        assertEquals(1, result.unavailableCount());
    }

    /** 单行超预算时保留说明，明确预览不可用。 */
    @Test
    void marksOversizedPreview() {
        var record = new DatasetRecord("ds-a", "run", 10, 20, "库存", List.of(), 1, true,
                "READY", null, null);
        when(datasets.listReady(10, 20)).thenReturn(List.of(record));
        when(datasets.readPage("ds-a", 10, 20, 0, 2)).thenThrow(new DatasetReadLimitException("超限"));
        var preview = service.listForAnalysis(identity).datasets().getFirst().preview();
        assertEquals("TOO_LARGE", preview.status());
        assertTrue(preview.truncated());
        assertTrue(preview.rows().isEmpty());
    }
}
