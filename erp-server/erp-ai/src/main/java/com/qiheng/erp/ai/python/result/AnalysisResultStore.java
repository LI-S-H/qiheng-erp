package com.qiheng.erp.ai.python.result;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.domain.entity.AiAnalysisResult;
import com.qiheng.erp.ai.mapper.AiAnalysisResultMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 保存分析结果文件及其元信息；读取时重新检查来源权限。
 *
 * @author Li
 * @since 2026-11-10
 */
@Service
public class AnalysisResultStore {
    private static final Logger log = LoggerFactory.getLogger(AnalysisResultStore.class);
    private final AiAnalysisResultMapper repository;
    private final DatasetManager datasets;
    private final ObjectMapper mapper;
    private final Path directory;
    private final long ttlDays;

    /** 初始化结果目录、保留天数及元信息和来源权限依赖。 */
    public AnalysisResultStore(AiAnalysisResultMapper repository, DatasetManager datasets, ObjectMapper mapper,
            @Value("${erp.ai.python.result-directory:./data/ai-python-results}") String directory,
            @Value("${erp.ai.python.result-ttl-days:0}") long ttlDays) {
        this.repository = repository;
        this.datasets = datasets;
        this.mapper = mapper;
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.ttlDays = ttlDays;
    }

    @FunctionalInterface
    public interface FileWriter {
        /** 将结果内容写入服务端临时文件。 */
        void write(OutputStream output) throws IOException;
    }

    /** 先写临时文件再登记元信息；登记失败时移除本次文件。 */
    public AiAnalysisResult save(AgentToolContext identity, String taskId, String executionId, String resultType,
            String name, String description, String fileType, List<String> sourceDatasetIds, FileWriter writer)
            throws IOException {
        verifySources(identity, sourceDatasetIds);
        String id = UUID.randomUUID().toString();
        if (taskId == null || !taskId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("分析任务 ID 无效");
        }
        Path relative = Path.of(Long.toString(identity.userId()), Long.toString(identity.conversationId()), taskId, id);
        Path target = directory.resolve(relative);
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "analysis-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                writer.write(output);
            }
            long size = Files.size(temporary);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            var record = new AiAnalysisResult();
            record.setResultId(id);
            record.setUserId(identity.userId());
            record.setConversationId(identity.conversationId());
            record.setAnalysisTaskId(taskId);
            record.setExecutionId(executionId);
            record.setResultType(resultType);
            record.setName(name);
            record.setDescription(description);
            record.setFileType(fileType);
            record.setFileSize(size);
            record.setStorageFile(relative.toString().replace('\\', '/'));
            record.setSourceDatasetIds(mapper.writeValueAsString(sourceDatasetIds));
            record.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            record.setExpiresAt(expiresAtFromNow(ttlDays));
            repository.insert(record);
            return record;
        } catch (IOException | RuntimeException error) {
            Files.deleteIfExists(target);
            throw error;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 列出当前会话中来源数据仍可访问的结果，避免逐条重复查询结果表。 */
    public List<AiAnalysisResult> list(AgentToolContext identity, String taskId) throws IOException {
        var records = repository.selectList(new LambdaQueryWrapper<AiAnalysisResult>()
                .eq(AiAnalysisResult::getUserId, identity.userId())
                .eq(AiAnalysisResult::getConversationId, identity.conversationId())
                .eq(AiAnalysisResult::getAnalysisTaskId, taskId)
                .orderByDesc(AiAnalysisResult::getCreatedAt));
        List<AiAnalysisResult> accessible = new ArrayList<>();
        for (var record : records) {
            try {
                List<String> sources = mapper.readValue(
                        record.getSourceDatasetIds(), new TypeReference<>() {});
                verifySources(identity, sources);
                if (!expired(record)) {
                    accessible.add(record);
                }
            } catch (IllegalArgumentException | IllegalStateException ignored) {
                // 来源权限或状态变化后不展示该结果。
            }
        }
        return accessible;
    }

    /** 校验归属和来源权限后返回结果元信息。 */
    public AiAnalysisResult find(String resultId, AgentToolContext identity) throws IOException {
        var record = repository.selectOne(new LambdaQueryWrapper<AiAnalysisResult>()
                .eq(AiAnalysisResult::getResultId, resultId)
                .eq(AiAnalysisResult::getUserId, identity.userId())
                .eq(AiAnalysisResult::getConversationId, identity.conversationId()));
        if (record == null) {
            throw new IllegalArgumentException("分析结果不存在");
        }
        if (expired(record)) {
            throw new IllegalStateException("分析结果已过期");
        }
        List<String> sources = mapper.readValue(record.getSourceDatasetIds(), new TypeReference<>() {});
        verifySources(identity, sources);
        return record;
    }

    /** 校验归属及来源后打开结果文件。 */
    public InputStream open(String resultId, AgentToolContext identity) throws IOException {
        return open(resultId, identity, false);
    }

    /** 打开文本结果时复用同一次元信息和权限检查。 */
    public InputStream open(String resultId, AgentToolContext identity, boolean textOnly) throws IOException {
        var record = find(resultId, identity);
        return openRecord(record, textOnly);
    }

    /**
     * 文本读取只核验一次权限，并返回同一次查询所得的文件说明。
     *
     * @param resultId 分析结果编号
     * @param identity 服务端确认的可信身份
     * @return 文件说明与完整文本
     * @throws IOException 文件读取失败
     */
    public TextResult readText(String resultId, AgentToolContext identity) throws IOException {
        var record = find(resultId, identity);
        try (var input = openRecord(record, true)) {
            return new TextResult(record.getName(), record.getFileType(),
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** 文件说明和完整文本由同一次读取返回。 */
    public record TextResult(String name, String fileType, String content) {
    }

    /** 使用已核验的元信息打开服务器文件。 */
    private InputStream openRecord(AiAnalysisResult record, boolean textOnly) throws IOException {
        if (textOnly && (record.getFileType() == null
                || !Set.of("txt", "csv", "json", "jsonl").contains(record.getFileType()))) {
            throw new IllegalArgumentException("该文件不是可按文本读取的结果");
        }
        Path file = directory.resolve(record.getStorageFile()).normalize();
        if (!file.startsWith(directory)) {
            throw new IllegalStateException("分析结果路径无效");
        }
        return Files.newInputStream(file);
    }

    /**
     * 清理已过期的结果文件：先删文件再删元信息，单条失败跳过并留待下次重试。
     *
     * @param limit 本次最多清理条数
     * @return 实际删除的元信息条数
     */
    public int purgeExpired(int limit) {
        LocalDateTime cutoff = LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        var records = repository.selectList(new LambdaQueryWrapper<AiAnalysisResult>()
                .isNotNull(AiAnalysisResult::getExpiresAt)
                .lt(AiAnalysisResult::getExpiresAt, cutoff)
                .orderByAsc(AiAnalysisResult::getExpiresAt)
                .last("LIMIT " + limit));
        int removed = 0;
        for (AiAnalysisResult record : records) {
            try {
                Path file = directory.resolve(record.getStorageFile()).normalize();
                if (file.startsWith(directory)) {
                    Files.deleteIfExists(file);
                }
                if (repository.deleteById(record.getResultId()) > 0) {
                    removed++;
                }
            } catch (IOException | RuntimeException error) {
                log.warn("过期分析结果清理失败 resultId={}", record.getResultId(), error);
            }
        }
        return removed;
    }

    /**
     * 按天对齐的过期时刻：当天零点（UTC）加保留天数。
     *
     * @param days 保留天数，0 表示不自动过期
     * @return 过期时刻；不启用时返回 null
     */
    private static LocalDateTime expiresAtFromNow(long days) {
        if (days <= 0) {
            return null;
        }
        return LocalDate.now(ZoneOffset.UTC).plusDays(days).atStartOfDay();
    }

    /** 已完成保留期判断；未设置过期时刻表示长期有效。 */
    private static boolean expired(AiAnalysisResult record) {
        return record.getExpiresAt() != null && !record.getExpiresAt().isAfter(LocalDateTime.now(ZoneOffset.UTC));
    }

    /** 同一会话中的来源数据集也必须仍然允许读取。 */
    private void verifySources(AgentToolContext identity, List<String> sourceIds) {
        for (String id : sourceIds) {
            var source = datasets.get(id, identity.userId(), identity.conversationId());
            if (!"READY".equals(source.status())) {
                throw new IllegalStateException("来源数据集尚未发布");
            }
        }
    }
}


