package com.qiheng.erp.ai.python.tool;

import com.qiheng.erp.ai.python.sandbox.PythonSandboxSession;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetInput;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import com.qiheng.erp.ai.context.AgentToolContext;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PythonDatasetPreparerTest {
    @Test
    void preparesAuthorizedRowsAndOrderedMetadata() throws Exception {
        var datasets = mock(DatasetManager.class);
        var sandbox = mock(PythonSandboxSession.class);
        var identity = new AgentToolContext(10, 20);
        var columns = List.of(new SqlQueryColumn("id", "商品", Types.BIGINT, "BIGINT", false),
                new SqlQueryColumn("name", "商品", Types.VARCHAR, "VARCHAR", true));
        var record = new DatasetRecord("ds_test", "run", 10, 20, "测试", columns, 1,
                false, "READY", null, null, "ROW_LIMIT");
        when(datasets.openReady("ds_test", 10, 20)).thenAnswer(call ->
                new DatasetInput(record, new ByteArrayInputStream("[1,\"箱\"]\n".getBytes(StandardCharsets.UTF_8))));
        Map<String, byte[]> files = new HashMap<>();
        doAnswer(call -> {
            files.put(call.getArgument(0), call.<InputStream>getArgument(1).readAllBytes());
            return null;
        }).when(sandbox).writeFile(anyString(), any(InputStream.class));
        doAnswer(call -> {
            files.put(call.getArgument(0), call.getArgument(1));
            return null;
        }).when(sandbox).writeFile(anyString(), any(byte[].class));

        var result = new PythonDatasetPreparer(datasets)
                .prepare(sandbox, identity, "ds_test");

        assertEquals("[1,\"箱\"]\n", new String(files.get("ds_test.jsonl"), StandardCharsets.UTF_8));
        assertEquals(1, files.size());
        assertEquals("ds_test", result.get("datasetId"));
        assertEquals("/tmp/erp-analysis/input/ds_test.jsonl", result.get("filePath"));
    }
}
