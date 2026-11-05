-- 保存实际截断原因；旧数据不推测原因，允许重复执行。
SET @dataset_reason_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_dataset' AND column_name = 'truncation_reason'
);
SET @dataset_reason_ddl = IF(@dataset_reason_exists = 0,
    'ALTER TABLE ai_dataset ADD COLUMN truncation_reason VARCHAR(16) NULL COMMENT ''截断原因：ROW_LIMIT / PAGE_LIMIT''',
    'SELECT 1');
PREPARE dataset_reason_stmt FROM @dataset_reason_ddl;
EXECUTE dataset_reason_stmt;
DEALLOCATE PREPARE dataset_reason_stmt;
