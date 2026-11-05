package com.qiheng.erp.ai.dataset;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiDataset;
import com.qiheng.erp.ai.mapper.AiDatasetMapper;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/** 使用 MyBatis-Plus 保存数据集元信息，所有读取均限定所属用户和会话。 */
@Repository
class MybatisDatasetRepository implements DatasetRepository {
    private final AiDatasetMapper mapper;
    private final ObjectMapper json;

    MybatisDatasetRepository(AiDatasetMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    /** 按 ID 和所属范围读取。 */
    @Override
    public Optional<DatasetRecord> find(String id, long userId, long conversationId) {
        return mapper.selectList(Wrappers.<AiDataset>lambdaQuery().eq(AiDataset::getDatasetId, id)
                .eq(AiDataset::getUserId, userId).eq(AiDataset::getConversationId, conversationId))
                .stream().findFirst().map(this::record);
    }

    /** 恢复同一执行轮次的暂存数据。 */
    @Override
    public Optional<DatasetRecord> findAttempt(String runId, int attempt, long userId, long conversationId) {
        return mapper.selectList(Wrappers.<AiDataset>lambdaQuery().eq(AiDataset::getSqlRunId, runId)
                .eq(AiDataset::getAttempt, attempt).eq(AiDataset::getUserId, userId)
                .eq(AiDataset::getConversationId, conversationId)).stream().findFirst().map(this::record);
    }

    /** 查询同次取数已发布的正式数据集。 */
    @Override
    public Optional<DatasetRecord> findReady(String runId, long userId, long conversationId) {
        return mapper.selectList(Wrappers.<AiDataset>lambdaQuery().eq(AiDataset::getReadyRunId, runId)
                .eq(AiDataset::getStatus, "READY").eq(AiDataset::getUserId, userId)
                .eq(AiDataset::getConversationId, conversationId)).stream().findFirst().map(this::record);
    }

    /** 清理时仅列出同次执行和同一用户会话的暂存数据。 */
    @Override
    public List<DatasetRecord> findStagedByRun(String runId, long userId, long conversationId) {
        return mapper.selectList(Wrappers.<AiDataset>lambdaQuery().eq(AiDataset::getSqlRunId, runId)
                .eq(AiDataset::getStatus, "STAGED").eq(AiDataset::getUserId, userId)
                .eq(AiDataset::getConversationId, conversationId)).stream().map(this::record).toList();
    }

    /** 获取来源 SQL 供权限复核。 */
    @Override
    public Optional<String> findSourceSql(String id, long userId, long conversationId) {
        return mapper.selectList(Wrappers.<AiDataset>lambdaQuery().select(AiDataset::getSourceSql)
                .eq(AiDataset::getDatasetId, id).eq(AiDataset::getUserId, userId)
                .eq(AiDataset::getConversationId, conversationId)).stream().findFirst().map(AiDataset::getSourceSql);
    }

    /** 插入元信息；时间以 UTC 明确写入，避免依赖数据库会话时区。 */
    @Override
    public void insert(DatasetRequest req, DatasetRecord data) {
        try {
            AiDataset row = new AiDataset();
            row.setDatasetId(data.datasetId());
            row.setSqlRunId(req.sqlRunId());
            row.setAttempt(req.attempt());
            row.setUserId(req.userId());
            row.setConversationId(req.conversationId());
            row.setDescription(req.description());
            row.setSourceSql(req.sql());
            row.setColumnsJson(json.writeValueAsString(data.columns()));
            row.setRowCount(data.rowCount());
            row.setCompleted(data.completed());
            row.setTruncationReason(data.truncationReason());
            row.setStorageFile(data.datasetId() + ".jsonl");
            row.setStatus(data.status());
            row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
            row.setExpiresAt(data.expiresAt() == null ? null : LocalDateTime.ofInstant(data.expiresAt(), ZoneOffset.UTC));
            mapper.insert(row);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("数据集字段信息无法保存", ex);
        }
    }

    /** 条件发布暂存结果。 */
    @Override
    public boolean approve(String id, long userId, long conversationId, SqlDataDescription description) {
        try {
            return mapper.approve(id, userId, conversationId, json.writeValueAsString(description)) == 1;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("数据集说明无法保存", ex);
        }
    }

    /** 条件删除暂存记录，不删除已发布结果。 */
    @Override
    public boolean discard(String id, long userId, long conversationId) {
        return mapper.delete(Wrappers.<AiDataset>lambdaQuery().eq(AiDataset::getDatasetId, id)
                .eq(AiDataset::getUserId, userId).eq(AiDataset::getConversationId, conversationId)
                .eq(AiDataset::getStatus, "STAGED")) == 1;
    }

    /** 将数据库 JSON 字段还原为结果列信息。 */
    private DatasetRecord record(AiDataset row) {
        try {
            List<SqlQueryColumn> columns = json.readValue(row.getColumnsJson(), new TypeReference<>() {});
            return new DatasetRecord(row.getDatasetId(), row.getSqlRunId(), row.getUserId(),
                    row.getConversationId(), row.getDescription(), columns, row.getRowCount(), row.getCompleted(),
                    row.getStatus(), row.getExpiresAt() == null ? null : row.getExpiresAt().toInstant(ZoneOffset.UTC),
                    row.getDataDescriptionJson() == null ? null : json.readValue(row.getDataDescriptionJson(), SqlDataDescription.class),
                    row.getTruncationReason());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("数据集字段信息无法读取", ex);
        }
    }
}
