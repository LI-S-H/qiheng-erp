-- 数据集按用户、会话和 SQL 执行归属，无需单独的研究标识。
SET @dataset_research_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_dataset' AND column_name = 'research_id'
);
SET @dataset_research_ddl = IF(@dataset_research_exists > 0,
    'ALTER TABLE ai_dataset DROP COLUMN research_id',
    'SELECT 1');
PREPARE dataset_research_stmt FROM @dataset_research_ddl;
EXECUTE dataset_research_stmt;
DEALLOCATE PREPARE dataset_research_stmt;
