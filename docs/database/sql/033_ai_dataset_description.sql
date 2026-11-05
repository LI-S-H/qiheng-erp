-- 数据集检查说明：新库已在 029 创建 JSON 字段，旧库按需补充。
-- 既有文本字段转换为 JSON 时由 MySQL 验证内容；无效数据会导致迁移失败，不会静默丢弃。
SET @dataset_description_type = (
    SELECT DATA_TYPE FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_dataset'
      AND column_name = 'data_description_json'
);
SET @dataset_description_ddl = CASE
    WHEN @dataset_description_type IS NULL THEN
        'ALTER TABLE ai_dataset ADD COLUMN data_description_json JSON NULL COMMENT ''检查通过后的数据集说明'''
    WHEN @dataset_description_type <> 'json' THEN
        'ALTER TABLE ai_dataset MODIFY COLUMN data_description_json JSON NULL COMMENT ''检查通过后的数据集说明'''
    ELSE 'SELECT 1'
END;
PREPARE dataset_description_stmt FROM @dataset_description_ddl;
EXECUTE dataset_description_stmt;
DEALLOCATE PREPARE dataset_description_stmt;
