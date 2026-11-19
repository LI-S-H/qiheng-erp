package com.qiheng.erp.ai.report.graph;

import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.dataset.DatasetUnavailableException;
import com.qiheng.erp.ai.domain.entity.AiAnalysisResult;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ReportMaterialPreparerTest {
    @Test
    void preparesOnlyAuthorizedMetadataAndMarksUnavailable() throws Exception {
        var identity = new AgentToolContext(1, 2);
        var datasets = mock(DatasetManager.class);
        var results = mock(AnalysisResultStore.class);
        when(datasets.get("ds_ok", 1, 2)).thenReturn(new DatasetRecord("ds_ok", "run", 1, 2,
                "库存", List.of(), 3, false, "READY", null, null));
        when(datasets.get("ds_old", 1, 2)).thenThrow(new DatasetUnavailableException("已过期"));
        var file = new AiAnalysisResult();
        file.setName("分析.csv");
        file.setFileType("csv");
        when(results.find("result_ok", identity)).thenReturn(file);
        var catalog = new ReportMaterialPreparer(datasets, results).prepare(
                Map.of("datasetIds", List.of("ds_ok", "ds_old"), "resultIds", List.of("result_ok")), identity);
        assertThat(catalog.toString()).contains("ds_ok", "UNAVAILABLE", "result_ok", "分析.csv",
                        "METADATA_AVAILABLE", "文件是否可读需通过读取工具确认")
                .doesNotContain("storageFile");
        verify(datasets).get("ds_ok", 1, 2);
        verify(results).find("result_ok", identity);
    }

    @Test
    void doesNotHideDatabaseFailure() {
        var datasets = mock(DatasetManager.class);
        when(datasets.get("ds_1", 1, 2)).thenThrow(new IllegalStateException("数据库不可用"));
        var preparer = new ReportMaterialPreparer(datasets, mock(AnalysisResultStore.class));
        assertThatThrownBy(() -> preparer.prepare(Map.of("datasetIds", List.of("ds_1"), "resultIds", List.of()),
                new AgentToolContext(1, 2))).hasMessageContaining("数据库不可用");
    }
}
