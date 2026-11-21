-- 经验提取：来源任务防重、经验目录摘要、检索索引。
-- 可重复执行：列、表、索引已存在时只跳过对应步骤。
-- 显式声明连接字符集，避免客户端默认 latin1 导致中文注释乱码。
SET NAMES utf8mb4;

USE erp;

-- 1) 来源任务 ID：同一任务只提取一次。
SET @source_column = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_experience'
      AND column_name = 'source_task_id');
SET @source_sql = IF(@source_column = 0,
    'ALTER TABLE ai_agent_experience ADD COLUMN source_task_id VARCHAR(192) NULL
        COMMENT ''来源任务 ID，用于防重复提取'' AFTER experience_text',
    'SELECT 1');
PREPARE source_stmt FROM @source_sql;
EXECUTE source_stmt;
DEALLOCATE PREPARE source_stmt;

-- 2) 证据引用列退出：经验只保留来源任务 ID。
SET @evidence_column = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_experience'
      AND column_name = 'evidence_refs');
SET @evidence_sql = IF(@evidence_column > 0,
    'ALTER TABLE ai_agent_experience DROP COLUMN evidence_refs',
    'SELECT 1');
PREPARE evidence_stmt FROM @evidence_sql;
EXECUTE evidence_stmt;
DEALLOCATE PREPARE evidence_stmt;

-- 3) 经验目录摘要：每个智能体一行，注入时只加载这一行。
CREATE TABLE IF NOT EXISTS ai_agent_experience_summary (
    agent_type VARCHAR(32) NOT NULL COMMENT '智能体类型：MAIN / ANALYSIS',
    summary    TEXT        NOT NULL COMMENT '该智能体的经验目录摘要，供模型判断是否检索',
    PRIMARY KEY (agent_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 经验目录摘要';

-- 4) 经验列表索引：按智能体取已发布经验。
SET @list_index = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_experience'
      AND index_name = 'idx_agent_experience_list');
SET @list_sql = IF(@list_index = 0,
    'ALTER TABLE ai_agent_experience ADD KEY idx_agent_experience_list
        (agent_type, status, deleted, id)',
    'SELECT 1');
PREPARE list_stmt FROM @list_sql;
EXECUTE list_stmt;
DEALLOCATE PREPARE list_stmt;

-- 5) 候选任务扫描索引：按类型和时间取最近任务。
SET @trace_index = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_trace'
      AND index_name = 'idx_agent_trace_extract');
SET @trace_sql = IF(@trace_index = 0,
    'ALTER TABLE ai_agent_trace ADD KEY idx_agent_trace_extract (agent_type, create_time)',
    'SELECT 1');
PREPARE trace_stmt FROM @trace_sql;
EXECUTE trace_stmt;
DEALLOCATE PREPARE trace_stmt;