package com.qiheng.erp.ai.python.result;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.domain.entity.AiAnalysisResult;
import com.qiheng.erp.ai.mapper.AiAnalysisResultMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AnalysisResultStoreTest {
    @TempDir Path directory;

    @Test
    void savesAndRechecksSourcesBeforeReading() throws Exception {
        var repository = mock(AiAnalysisResultMapper.class);
        var datasets = mock(DatasetManager.class);
        var source = new DatasetRecord("ds_test", "run", 10, 20, "测试", List.of(), 1,
                true, "READY", null, null);
        when(datasets.get("ds_test", 10, 20)).thenReturn(source);
        var stored = new AtomicReference<AiAnalysisResult>();
        when(repository.insert(any(AiAnalysisResult.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return 1;
        });
        when(repository.selectOne(any())).thenAnswer(invocation -> stored.get());
        when(repository.selectList(any())).thenAnswer(invocation -> List.of(stored.get()));
        var store = new AnalysisResultStore(repository, datasets, new ObjectMapper(), directory.toString());
        var identity = new AgentToolContext(10, 20);
        var result = store.save(identity, "task", "execution", "FILE", "库存.csv", null, "csv",
                List.of("ds_test"), out -> out.write("内容".getBytes(StandardCharsets.UTF_8)));
        assertEquals("库存.csv", result.getName());
        assertFalse(result.getStorageFile().contains("库存"));
        try (var input = store.open(result.getResultId(), identity)) {
            assertEquals("内容", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var input = store.open(result.getResultId(), identity, true)) {
            assertEquals("内容", new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(1, store.list(identity, "task").size());
        // 列表直接检查已查询记录的来源，不应再逐条查询结果表。
        verify(repository, times(2)).selectOne(any());
        when(datasets.get("ds_test", 10, 20)).thenThrow(new IllegalArgumentException("权限已撤销"));
        assertThrows(IllegalArgumentException.class, () -> store.open(result.getResultId(), identity));
        assertTrue(store.list(identity, "task").isEmpty());
    }

    @Test
    void deletesFileWhenDatabaseWriteFails() throws Exception {
        var repository = mock(AiAnalysisResultMapper.class);
        when(repository.insert(any(AiAnalysisResult.class))).thenThrow(new IllegalStateException("数据库失败"));
        var store = new AnalysisResultStore(repository, mock(DatasetManager.class), new ObjectMapper(), directory.toString());
        assertThrows(IllegalStateException.class, () -> store.save(new AgentToolContext(10, 20), "task", "run", "TEXT",
                "结果", null, "txt", List.of(), out -> out.write(1)));
        try (var files = Files.walk(directory)) {
            assertEquals(0, files.filter(Files::isRegularFile).count());
        }
    }
}
