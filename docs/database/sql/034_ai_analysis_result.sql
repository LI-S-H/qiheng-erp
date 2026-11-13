CREATE TABLE IF NOT EXISTS ai_analysis_result (
    result_id VARCHAR(64) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    analysis_task_id VARCHAR(64) NOT NULL,
    execution_id VARCHAR(64) NOT NULL,
    result_type VARCHAR(16) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(500) NULL,
    file_type VARCHAR(32) NULL,
    file_size BIGINT NOT NULL,
    storage_file VARCHAR(255) NOT NULL,
    source_dataset_ids JSON NOT NULL,
    created_at DATETIME(6) NOT NULL COMMENT 'UTC',
    KEY idx_analysis_result_owner (user_id, conversation_id, created_at),
    KEY idx_analysis_result_task (user_id, conversation_id, analysis_task_id, execution_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 分析结果元信息';
