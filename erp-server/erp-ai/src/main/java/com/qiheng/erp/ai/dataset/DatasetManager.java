package com.qiheng.erp.ai.dataset;

import com.fasterxml.jackson.core.type.TypeReference;
import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.qiheng.erp.ai.sql.execution.model.QueryResult;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.time.Instant;

/** 管理查询数据文件与元信息；暂存结果经检查发布后才对其他模块可见。 */
@Service
public class DatasetManager {
    private static final int MAX_PAGE_CHARS = 8000;
    private final DatasetRepository repository;
    private final Path directory;
    private final ObjectMapper mapper;
    private final long ttlSeconds;
    private final DatasetAccess access;

    DatasetManager(DatasetRepository repository, DatasetAccess access, ObjectMapper mapper,
                   @Value("${erp.ai.dataset.directory:./data/ai-datasets}") String directory,
                   @Value("${erp.ai.dataset.ttl-seconds:0}") long ttlSeconds) {
        this.repository = repository;
        this.access = access;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.ttlSeconds = ttlSeconds;
        this.mapper = mapper.copy().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    }

    /** 将查询结果暂存；相同执行轮次恢复时复用已有文件和元信息。 */
    public DatasetRecord stage(DatasetRequest request, QueryResult result) {
        var ready = repository.findReady(request.sqlRunId(), request.userId(), request.conversationId());
        if (ready.isPresent()) return available(ready.get());
        var existing = repository.findAttempt(request.sqlRunId(), request.attempt(), request.userId(), request.conversationId());
        if (existing.isPresent()) return available(existing.get());
        String id = "ds_" + IdUtil.simpleUUID();
        DatasetRecord record = new DatasetRecord(id, request.sqlRunId(), request.userId(),
                request.conversationId(), request.description(), result.columns(), result.rowCount(),
                result.completed(), "STAGED",
                ttlSeconds > 0 ? Instant.now().plusSeconds(ttlSeconds) : null, null, result.truncationReason());
        Path file = directory.resolve(id + ".jsonl");
        try {
            Files.createDirectories(directory);
            // 行采用 JSON 数组，字段顺序与元信息一致，避免重复列别名覆盖数据。
            Path temporary = Files.createTempFile(directory, "dataset-", ".tmp");
            try {
                try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    for (List<Object> row : result.rows()) {
                        writer.write(mapper.writeValueAsString(row));
                        writer.newLine();
                    }
                }
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
            repository.insert(request, record);
            return record;
        } catch (DuplicateKeyException ex) {
            deleteFile(file);
            return available(repository.findAttempt(request.sqlRunId(), request.attempt(), request.userId(), request.conversationId())
                    .orElseThrow(() -> ex));
        } catch (IOException | RuntimeException ex) {
            deleteFile(file);
            throw new IllegalStateException("数据集暂存失败", ex);
        }
    }

    /** 将已通过检查的暂存结果发布，数据库唯一约束保证同次执行只有一个正式结果。 */
    public DatasetRecord approve(String id, long userId, long conversationId, SqlDataDescription description) {
        DatasetRecord record = available(owned(id, userId, conversationId));
        if ("READY".equals(record.status())) return record;
        try {
            repository.approve(id, userId, conversationId, description);
        } catch (DuplicateKeyException ex) {
            DatasetRecord existing = available(repository.findReady(record.sqlRunId(), userId, conversationId)
                    .orElseThrow(() -> ex));
            // 旧检查点可能仍引用暂存数据，待图结束并确认后再清理。
            return existing;
        }
        DatasetRecord ready = owned(id, userId, conversationId);
        if (!"READY".equals(ready.status())) throw new IllegalStateException("数据集未能发布");
        return ready;
    }

    /** 丢弃未通过检查的暂存结果，正式数据集不会被删除。 */
    void discard(String id, long userId, long conversationId) {
        var record = repository.find(id, userId, conversationId);
        if (record.isPresent() && "STAGED".equals(record.get().status())) {
            // 文件先删除，失败时保留数据库记录供后续重试。
            deleteFile(directory.resolve(id + ".jsonl"));
            repository.discard(id, userId, conversationId);
        }
    }

    /** 仅在该次执行已完成且检查点不再引用暂存结果时调用。 */
    public void cleanupStaged(String sqlRunId, long userId, long conversationId) {
        for (DatasetRecord record : repository.findStagedByRun(sqlRunId, userId, conversationId)) {
            discard(record.datasetId(), userId, conversationId);
        }
    }

    /** 读取正式数据；数据集 ID 不是权限凭据，必须同时检查归属。 */
    public List<List<Object>> read(String id, long userId, long conversationId) {
        DatasetRecord record = available(owned(id, userId, conversationId));
        if (!"READY".equals(record.status())) throw new IllegalStateException("数据集尚未发布");
        return readRows(record, Integer.MAX_VALUE);
    }

    /** 按行分页读取正式数据，限制输出大小并重新检查当前权限。 */
    public DatasetPage readPage(String id, long userId, long conversationId, int offset, int limit) {
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("offset 不得小于 0，limit 必须大于 0");
        }
        DatasetRecord record = available(owned(id, userId, conversationId));
        if (!"READY".equals(record.status())) throw new IllegalStateException("数据集尚未发布");
        List<List<Object>> rows = new ArrayList<>();
        boolean budgetExceeded = false;
        try (BufferedReader reader = Files.newBufferedReader(directory.resolve(id + ".jsonl"), StandardCharsets.UTF_8)) {
            int used = mapper.writeValueAsString(record.columns()).length();
            // 预留结果字段和数值的 JSON 包装空间。
            if (used > MAX_PAGE_CHARS - 1024) throw new DatasetReadLimitException("数据集列信息超出读取上限");
            for (long index = 0; index < (long) offset + limit; index++) {
                // 逐字符限制单行长度，避免 readLine 一次装入超大字符串。
                StringBuilder line = new StringBuilder();
                int character;
                while ((character = reader.read()) != -1 && character != '\n') {
                    if (line.length() >= MAX_PAGE_CHARS) throw new DatasetReadLimitException("数据集单行超出读取上限");
                    line.append((char) character);
                }
                if (character == -1 && line.isEmpty()) break;
                if (index < offset) continue;
                int next = used + line.length() + 1;
                if (next > MAX_PAGE_CHARS - 1024) {
                    if (rows.isEmpty()) throw new DatasetReadLimitException("数据集单行及列信息超出读取上限");
                    budgetExceeded = true;
                    break;
                }
                rows.add(mapper.readValue(line.toString(), new TypeReference<>() {}));
                used = next;
            }
        } catch (IOException ex) {
            throw new IllegalStateException("数据集文件无法读取", ex);
        }
        boolean hasMore = (long) offset + rows.size() < record.rowCount();
        return new DatasetPage(record.columns(), rows, record.rowCount(), rows.size(), hasMore,
                budgetExceeded, hasMore ? offset + rows.size() : null, !record.completed(),
                record.completenessMessage());
    }

    /** 供结果检查节点读取有限样本，不将整个结果写进图状态。 */
    public List<List<Object>> readStaged(String id, long userId, long conversationId, int maxRows) {
        return readRows(available(owned(id, userId, conversationId)), maxRows);
    }

    /** 恢复时按业务执行 ID 查找已完成的数据集。 */
    public java.util.Optional<DatasetRecord> findReady(String sqlRunId, long userId, long conversationId) {
        return repository.findReady(sqlRunId, userId, conversationId).map(this::available);
    }

    /** 供结果检查节点读取已授权的数据集元信息。 */
    public DatasetRecord get(String id, long userId, long conversationId) {
        return available(owned(id, userId, conversationId));
    }

    private DatasetRecord owned(String id, long userId, long conversationId) {
        return repository.find(id, userId, conversationId)
                .orElseThrow(() -> new IllegalArgumentException("数据集不存在或无权访问"));
    }

    /** 过期或文件缺失的引用不能当作可恢复的数据集。 */
    private DatasetRecord available(DatasetRecord record) {
        if (record.expiresAt() != null && !record.expiresAt().isAfter(Instant.now())) {
            throw new IllegalStateException("数据集已过期");
        }
        if (!Files.isRegularFile(directory.resolve(record.datasetId() + ".jsonl"))) {
            throw new IllegalStateException("数据集文件不存在");
        }
        access.verify(record);
        return record;
    }

    private List<List<Object>> readRows(DatasetRecord record, int maxRows) {
        List<List<Object>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(directory.resolve(record.datasetId() + ".jsonl"), StandardCharsets.UTF_8)) {
            String line;
            while (rows.size() < maxRows && (line = reader.readLine()) != null) {
                rows.add(mapper.readValue(line, new TypeReference<>() {}));
            }
            return rows;
        } catch (IOException ex) {
            throw new IllegalStateException("数据集文件无法读取", ex);
        }
    }

    private void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ex) {
            throw new IllegalStateException("数据集文件无法清理", ex);
        }
    }
}
