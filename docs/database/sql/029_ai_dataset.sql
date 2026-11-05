-- SQL 子图查询结果的持久引用。真实数据保存于配置的数据文件目录。
CREATE TABLE IF NOT EXISTS ai_dataset (
    dataset_id VARCHAR(64) NOT NULL PRIMARY KEY,
    sql_run_id VARCHAR(128) NOT NULL,
    attempt INT NOT NULL,
    ready_run_id VARCHAR(128) NULL,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NOT NULL,
    description VARCHAR(500) NOT NULL,
    source_sql TEXT NOT NULL,
    columns_json TEXT NOT NULL,
    data_description_json TEXT NULL COMMENT '检查通过后的数据集说明',
    row_count INT NOT NULL,
    completed BOOLEAN NOT NULL,
    truncation_reason VARCHAR(16) NULL COMMENT '截断原因：ROW_LIMIT / PAGE_LIMIT；历史数据可为空',
    storage_file VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL,
    expires_at DATETIME(6) NULL COMMENT 'UTC，空表示不自动过期',
    created_at DATETIME(6) NOT NULL COMMENT 'UTC',
    UNIQUE KEY uk_ai_dataset_attempt (sql_run_id, attempt),
    UNIQUE KEY uk_ai_dataset_ready_run (ready_run_id),
    KEY idx_ai_dataset_owner (user_id, conversation_id, dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 查询数据集元信息';
