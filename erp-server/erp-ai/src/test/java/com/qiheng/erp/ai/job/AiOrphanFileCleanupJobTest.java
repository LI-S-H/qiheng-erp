package com.qiheng.erp.ai.job;

import com.qiheng.erp.ai.domain.entity.AiDataset;
import com.qiheng.erp.ai.mapper.AiAnalysisResultMapper;
import com.qiheng.erp.ai.mapper.AiDatasetMapper;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AiOrphanFileCleanupJobTest {
    @TempDir Path directory;

    @Test
    void scansMoreThanOneBatchInOneRun() throws Exception {
        var datasets = mock(AiDatasetMapper.class);
        var results = mock(AiAnalysisResultMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemExceptionMqPublisher> publisher = mock(ObjectProvider.class);
        for (int i = 0; i < 205; i++) {
            Path file = Files.createFile(directory.resolve("ds_" + String.format("%032x", i) + ".jsonl"));
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().minus(20, ChronoUnit.DAYS)));
        }
        new AiOrphanFileCleanupJob(datasets, results, publisher, directory.toString(),
                directory.resolve("results").toString(), 15, 30).cleanup();
        try (var files = Files.list(directory)) {
            assertThat(files.count()).isZero();
        }
        verify(datasets, times(4)).selectBatchIds(anyCollection());
    }

    @Test
    void disabledRetentionKeepsOldFiles() throws Exception {
        var datasets = mock(AiDatasetMapper.class);
        var results = mock(AiAnalysisResultMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemExceptionMqPublisher> publisher = mock(ObjectProvider.class);
        Path old = Files.createFile(directory.resolve("ds_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.jsonl"));
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(20, ChronoUnit.DAYS)));
        new AiOrphanFileCleanupJob(datasets, results, publisher, directory.toString(),
                directory.resolve("results").toString(), 0, 0).cleanup();
        assertThat(old).exists();
        verifyNoInteractions(datasets, results);
    }

    @Test
    void reportsDatabaseFailureWithoutDeletingFile() throws Exception {
        var datasets = mock(AiDatasetMapper.class);
        var results = mock(AiAnalysisResultMapper.class);
        var mq = mock(SystemExceptionMqPublisher.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemExceptionMqPublisher> publisher = mock(ObjectProvider.class);
        when(publisher.getIfAvailable()).thenReturn(mq);
        String id = "ds_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        Path old = Files.createFile(directory.resolve(id + ".jsonl"));
        Files.setLastModifiedTime(old, FileTime.from(Instant.now().minus(20, ChronoUnit.DAYS)));
        when(datasets.selectBatchIds(anyCollection())).thenThrow(new IllegalStateException("database unavailable"));
        new AiOrphanFileCleanupJob(datasets, results, publisher, directory.toString(),
                directory.resolve("results").toString(), 15, 30).cleanup();
        assertThat(old).exists();
        verify(mq).publish(argThat(message -> message.getDetailSummary().contains(id)));
    }

    @Test
    void removesOldUnregisteredFilesAndKeepsRegisteredOrRecentFiles() throws Exception {
        var datasets = mock(AiDatasetMapper.class);
        var results = mock(AiAnalysisResultMapper.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<SystemExceptionMqPublisher> publisher = mock(ObjectProvider.class);
        String orphan = "ds_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String saved = "ds_bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String anotherOrphan = "ds_cccccccccccccccccccccccccccccccc";
        Path old = Files.createFile(directory.resolve(orphan + ".jsonl"));
        Path existing = Files.createFile(directory.resolve(saved + ".jsonl"));
        Path anotherOld = Files.createFile(directory.resolve(anotherOrphan + ".jsonl"));
        Path recent = Files.createFile(directory.resolve("ds_dddddddddddddddddddddddddddddddd.jsonl"));
        FileTime date = FileTime.from(Instant.now().minus(20, ChronoUnit.DAYS));
        Files.setLastModifiedTime(old, date);
        Files.setLastModifiedTime(existing, date);
        Files.setLastModifiedTime(anotherOld, date);
        var record = new AiDataset();
        record.setDatasetId(saved);
        when(datasets.selectBatchIds(anyCollection())).thenReturn(java.util.List.of(record));
        new AiOrphanFileCleanupJob(datasets, results, publisher, directory.toString(),
                directory.resolve("results").toString(), 15, 30).cleanup();
        assertThat(old).doesNotExist();
        assertThat(existing).exists();
        assertThat(anotherOld).doesNotExist();
        assertThat(recent).exists();
    }
}
