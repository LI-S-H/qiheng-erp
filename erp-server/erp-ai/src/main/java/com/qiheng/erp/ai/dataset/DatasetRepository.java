package com.qiheng.erp.ai.dataset;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

/** 数据集元信息存储，文件写入和查询结果发布由管理器协调。 */
interface DatasetRepository {
    Optional<DatasetRecord> find(String datasetId, long userId, long conversationId);
    Optional<DatasetRecord> findAttempt(String sqlRunId, int attempt, long userId, long conversationId);
    Optional<DatasetRecord> findReady(String sqlRunId, long userId, long conversationId);
    List<DatasetRecord> findReadyByConversation(long userId, long conversationId);
    List<DatasetRecord> findStagedByRun(String sqlRunId, long userId, long conversationId);
    Optional<String> findSourceSql(String datasetId, long userId, long conversationId);
    void insert(DatasetRequest request, DatasetRecord record);
    boolean approve(String datasetId, long userId, long conversationId, SqlDataDescription description);
    boolean discard(String datasetId, long userId, long conversationId);

    /** 查询已过期的数据集，按到期时间升序，最多返回 limit 条。 */
    List<DatasetRecord> findExpired(LocalDateTime cutoff, int limit);

    /** 删除一条过期数据集的元信息。 */
    boolean delete(String datasetId);
}
