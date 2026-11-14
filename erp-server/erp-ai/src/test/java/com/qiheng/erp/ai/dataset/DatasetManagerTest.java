package com.qiheng.erp.ai.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.sql.execution.model.QueryResult;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Types;
import java.time.LocalDate;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatasetManagerTest {
    @TempDir Path directory;
    private static final SqlDataDescription DESCRIPTION = new SqlDataDescription("库存", "每个商品一行", List.of(), "");

    @Test
    void listsOnlyAvailableDatasetsInTheRequestedConversation() throws Exception {
        DatasetRepository repository = mock(DatasetRepository.class);
        DatasetAccess access = mock(DatasetAccess.class);
        DatasetManager manager = new DatasetManager(repository, access, new ObjectMapper(), directory.toString(), 0);
        var ready = new DatasetRecord("ds_ok", "run-1", 10, 20, "库存", List.of(), 1, true, "READY", null, null);
        var denied = new DatasetRecord("ds_denied", "run-2", 10, 20, "库存", List.of(), 1, true, "READY", null, null);
        var expired = new DatasetRecord("ds_expired", "run-3", 10, 20, "库存", List.of(), 1, true, "READY", Instant.EPOCH, null);
        var missing = new DatasetRecord("ds_missing", "run-4", 10, 20, "库存", List.of(), 1, true, "READY", null, null);
        Files.writeString(directory.resolve("ds_ok.jsonl"), "[]\n");
        Files.writeString(directory.resolve("ds_denied.jsonl"), "[]\n");
        when(repository.findReadyByConversation(10, 20)).thenReturn(List.of(ready, denied, expired, missing));
        doThrow(new IllegalStateException("权限不足")).when(access).verify(denied);

        assertEquals(List.of(ready), manager.listReady(10, 20));
        assertTrue(manager.listReady(11, 20).isEmpty());
        verify(repository).findReadyByConversation(10, 20);
    }

    /** 启用保留天数时，暂存数据集写入按天对齐的过期时刻。 */
    @Test
    void stagesDatasetWithDayAlignedExpiry() {
        DatasetRepository repository = mock(DatasetRepository.class);
        DatasetAccess access = mock(DatasetAccess.class);
        DatasetManager manager = new DatasetManager(repository, access, new ObjectMapper(), directory.toString(), 15);
        var captured = new java.util.concurrent.atomic.AtomicReference<DatasetRecord>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return null;
        }).when(repository).insert(any(DatasetRequest.class), any(DatasetRecord.class));

        var request = new DatasetRequest("run-exp", 10, 20, "库存", "SELECT 1", 1);
        manager.stage(request, new com.qiheng.erp.ai.sql.execution.model.QueryResult(
                List.of(), List.of(), 0, true, 1, 1, "hash"));

        var expected = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(15)
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
        assertEquals(expected, captured.get().expiresAt());
    }

    @Test
    void opensOnlyAuthorizedPublishedDatasetWithoutChangingItsRows() throws Exception {
        DatasetRepository repository = mock(DatasetRepository.class);
        DatasetAccess access = mock(DatasetAccess.class);
        DatasetManager manager = new DatasetManager(repository, access, new ObjectMapper(), directory.toString(), 0);
        var record = new DatasetRecord("ds_ready", "run-1", 10, 20, "库存", List.of(), 1,
                false, "READY", null, DESCRIPTION, "ROW_LIMIT");
        String rows = "[\"商品A\",10]\n";
        Files.writeString(directory.resolve("ds_ready.jsonl"), rows);
        when(repository.find("ds_ready", 10, 20)).thenReturn(Optional.of(record));

        try (var input = manager.openReady("ds_ready", 10, 20)) {
            assertEquals(record, input.record());
            assertEquals(rows, new String(input.stream().readAllBytes(), StandardCharsets.UTF_8));
        }
        verify(access).verify(record);
        assertThrows(IllegalArgumentException.class,
                () -> manager.openReady("ds_ready", 11, 20));

        var staged = new DatasetRecord("ds_ready", "run-1", 10, 20, "库存", List.of(), 1,
                true, "STAGED", null, null);
        when(repository.find("ds_ready", 10, 20)).thenReturn(Optional.of(staged));
        assertThrows(IllegalStateException.class,
                () -> manager.openReady("ds_ready", 10, 20));
    }

    @Test
    void duplicatePublicationKeepsStagedFileForCheckpointRecovery() throws Exception {
        DatasetRepository repository = mock(DatasetRepository.class);
        DatasetManager manager = new DatasetManager(repository, record -> {}, new ObjectMapper(), directory.toString(), 0);
        DatasetRecord staged = new DatasetRecord("ds_staged", "run-1", 10, 20,
                "库存", List.of(), 0, true, "STAGED", null, null);
        DatasetRecord ready = new DatasetRecord("ds_ready", "run-1", 10, 20,
                "库存", List.of(), 0, true, "READY", null, DESCRIPTION);
        Files.writeString(directory.resolve("ds_staged.jsonl"), "");
        Files.writeString(directory.resolve("ds_ready.jsonl"), "");
        when(repository.find("ds_staged", 10, 20)).thenReturn(Optional.of(staged));
        when(repository.approve("ds_staged", 10, 20, DESCRIPTION)).thenThrow(new DuplicateKeyException("已有正式结果"));
        when(repository.findReady("run-1", 10, 20)).thenReturn(Optional.of(ready));
        assertEquals(ready, manager.approve("ds_staged", 10, 20, DESCRIPTION));
        assertTrue(Files.exists(directory.resolve("ds_staged.jsonl")));
        verify(repository, never()).discard(anyString(), anyLong(), anyLong());
    }

    @Test
    void cleanupRemovesOnlyStagedFilesAndKeepsReady() throws Exception {
        DatasetRepository repository = mock(DatasetRepository.class);
        DatasetManager manager = new DatasetManager(repository, record -> {}, new ObjectMapper(), directory.toString(), 0);
        DatasetRecord staged = new DatasetRecord("ds_staged", "run-1", 10, 20,
                "库存", List.of(), 0, true, "STAGED", null, null);
        DatasetRecord ready = new DatasetRecord("ds_ready", "run-1", 10, 20,
                "库存", List.of(), 0, true, "READY", null, DESCRIPTION);
        Files.writeString(directory.resolve("ds_staged.jsonl"), "");
        Files.writeString(directory.resolve("ds_ready.jsonl"), "");
        when(repository.findStagedByRun("run-1", 10, 20)).thenReturn(List.of(staged));
        when(repository.find("ds_staged", 10, 20)).thenReturn(Optional.of(staged));
        when(repository.find("ds_ready", 10, 20)).thenReturn(Optional.of(ready));
        manager.cleanupStaged("run-1", 10, 20);
        manager.discard("ds_ready", 10, 20);
        assertFalse(Files.exists(directory.resolve("ds_staged.jsonl")));
        assertTrue(Files.exists(directory.resolve("ds_ready.jsonl")));
        verify(repository).discard("ds_staged", 10, 20);
        verify(repository, never()).discard("ds_ready", 10, 20);
    }

    @Test
    void stagingPreservesValuesAndRequiresPublicationBeforeNormalRead() {
        DatasetRepository repository = mock(DatasetRepository.class);
        DatasetManager manager = new DatasetManager(repository, record -> {}, new ObjectMapper(), directory.toString(), 0);
        DatasetRequest request = new DatasetRequest("run-1", 10, 20, "库存", "SELECT ...", 1);
        var columns = List.of(new SqlQueryColumn("amount", "amount", Types.DECIMAL, "DECIMAL", true),
                new SqlQueryColumn("day", "day", Types.DATE, "DATE", true),
                new SqlQueryColumn("x", "amount", Types.VARCHAR, "VARCHAR", true));
        var rows = List.<List<Object>>of(Arrays.asList(new BigDecimal("1234567890.123456789"),
                LocalDate.of(2026, 10, 27), null));
        var result = new QueryResult(columns, rows, 1, true, 1, 1, "hash");

        DatasetRecord staged = manager.stage(request, result);
        when(repository.find(staged.datasetId(), 10, 20)).thenReturn(Optional.of(staged));
        assertThrows(IllegalStateException.class, () -> manager.read(staged.datasetId(), 10, 20));
        var sample = manager.readStaged(staged.datasetId(), 10, 20, 1);
        assertEquals(new BigDecimal("1234567890.123456789"), sample.getFirst().getFirst());
        assertEquals("2026-10-27", sample.getFirst().get(1));
        assertNull(sample.getFirst().get(2));
        assertThrows(IllegalArgumentException.class, () -> manager.readStaged(staged.datasetId(), 11, 20, 1));

        DatasetRecord ready = new DatasetRecord(staged.datasetId(), staged.sqlRunId(), 10, 20,
                staged.description(), staged.columns(), 1, true, "READY", null, DESCRIPTION);
        when(repository.find(staged.datasetId(), 10, 20)).thenReturn(Optional.of(staged), Optional.of(ready), Optional.of(ready));
        when(repository.approve(staged.datasetId(), 10, 20, DESCRIPTION)).thenReturn(true);
        assertEquals(DESCRIPTION, manager.approve(staged.datasetId(), 10, 20, DESCRIPTION).dataDescription());
        assertEquals(sample, manager.read(staged.datasetId(), 10, 20));
    }

    @Test
    void sameExecutionAttemptReusesPreviouslyStagedDataset() throws Exception {
        DatasetRepository repository = mock(DatasetRepository.class);
        DatasetManager manager = new DatasetManager(repository, record -> {}, new ObjectMapper(), directory.toString(), 0);
        DatasetRequest request = new DatasetRequest("run-2", 10, 20, "库存", "SELECT ...", 1);
        DatasetRecord previous = new DatasetRecord("ds_previous", "run-2", 10, 20,
                "库存", List.of(), 0, true, "STAGED", null, null);
        Files.writeString(directory.resolve("ds_previous.jsonl"), "");
        when(repository.findAttempt("run-2", 1, 10, 20)).thenReturn(Optional.of(previous));
        var result = new QueryResult(List.of(), List.of(), 0, true, 1, 1, "hash");
        assertEquals(previous, manager.stage(request, result));
        verify(repository, never()).insert(any(), any());
    }

    @Test
    void expiredOrMissingDatasetCannotBeRead() {
        DatasetRepository repository = mock(DatasetRepository.class);
        DatasetManager manager = new DatasetManager(repository, record -> {}, new ObjectMapper(), directory.toString(), 0);
        DatasetRecord expired = new DatasetRecord("ds_expired", "run-3", 10, 20,
                "库存", List.of(), 0, true, "READY", Instant.now().minusSeconds(1), DESCRIPTION);
        when(repository.find("ds_expired", 10, 20)).thenReturn(Optional.of(expired));
        assertEquals("数据集已过期", assertThrows(IllegalStateException.class,
                () -> manager.read("ds_expired", 10, 20)).getMessage());
        DatasetRecord missing = new DatasetRecord("ds_missing", "run-4", 10, 20,
                "库存", List.of(), 0, true, "READY", null, DESCRIPTION);
        when(repository.find("ds_missing", 10, 20)).thenReturn(Optional.of(missing));
        assertEquals("数据集文件不存在", assertThrows(IllegalStateException.class,
                () -> manager.read("ds_missing", 10, 20)).getMessage());
    }
}
