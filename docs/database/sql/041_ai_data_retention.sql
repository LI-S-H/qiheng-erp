-- 数据生命周期：清理任务需要的索引与分析结果有效期。
-- 可重复执行：索引与列已存在时只跳过。
USE erp;

SET NAMES utf8mb4;

SET @result_expires_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_analysis_result' AND column_name = 'expires_at'
);
SET @result_expires_sql = IF(@result_expires_exists = 0,
    'ALTER TABLE ai_analysis_result ADD COLUMN expires_at DATETIME NULL COMMENT ''UTC 过期时刻，按天对齐；NULL 表示不自动过期'' AFTER created_at',
    'SELECT 1');
PREPARE result_expires_stmt FROM @result_expires_sql;
EXECUTE result_expires_stmt;
DEALLOCATE PREPARE result_expires_stmt;

SET @result_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_analysis_result' AND index_name = 'idx_analysis_result_expires'
);
SET @result_index_sql = IF(@result_index_exists = 0,
    'ALTER TABLE ai_analysis_result ADD KEY idx_analysis_result_expires (expires_at)',
    'SELECT 1');
PREPARE result_index_stmt FROM @result_index_sql;
EXECUTE result_index_stmt;
DEALLOCATE PREPARE result_index_stmt;

SET @dataset_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_dataset' AND index_name = 'idx_ai_dataset_expires'
);
SET @dataset_index_sql = IF(@dataset_index_exists = 0,
    'ALTER TABLE ai_dataset ADD KEY idx_ai_dataset_expires (expires_at)',
    'SELECT 1');
PREPARE dataset_index_stmt FROM @dataset_index_sql;
EXECUTE dataset_index_stmt;
DEALLOCATE PREPARE dataset_index_stmt;