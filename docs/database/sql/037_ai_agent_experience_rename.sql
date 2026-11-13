-- 经验表跟随新的智能体分层改名：旧节点记忆链路退出后，经验按智能体类型分类。
-- 可重复执行：表名与列名已改过时只补注释和索引名。
-- 显式声明连接字符集，避免客户端默认 latin1 导致中文注释乱码。
SET NAMES utf8mb4;

USE erp;

SET @old_table = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'ai_node_experience');
SET @new_table = (SELECT COUNT(*) FROM information_schema.tables
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_experience');
SET @rename_sql = IF(@old_table > 0 AND @new_table = 0,
    'RENAME TABLE ai_node_experience TO ai_agent_experience', 'SELECT 1');
PREPARE rename_stmt FROM @rename_sql;
EXECUTE rename_stmt;
DEALLOCATE PREPARE rename_stmt;

SET @old_column = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_experience' AND column_name = 'node_code');
SET @new_column = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_experience' AND column_name = 'agent_type');
SET @column_sql = IF(@old_column > 0 AND @new_column = 0,
    'ALTER TABLE ai_agent_experience CHANGE COLUMN node_code agent_type VARCHAR(32) NOT NULL
        COMMENT ''智能体类型：MAIN 主智能体 / ANALYSIS 分析智能体 / SQL_GENERATE SQL 生成 / SQL_INSPECT 结果校验''',
    'SELECT 1');
PREPARE column_stmt FROM @column_sql;
EXECUTE column_stmt;
DEALLOCATE PREPARE column_stmt;

SET @evidence_column = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_experience' AND column_name = 'evidence_refs');
SET @evidence_sql = IF(@evidence_column > 0,
    'ALTER TABLE ai_agent_experience MODIFY COLUMN evidence_refs JSON DEFAULT NULL
        COMMENT ''经验依据的智能体流水编号（ai_agent_trace.id）列表''',
    'SELECT 1');
PREPARE evidence_stmt FROM @evidence_sql;
EXECUTE evidence_stmt;
DEALLOCATE PREPARE evidence_stmt;

SET @old_index = (SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_agent_experience'
      AND index_name = 'idx_ai_node_experience_query');
SET @index_sql = IF(@old_index > 0,
    'ALTER TABLE ai_agent_experience RENAME INDEX idx_ai_node_experience_query TO idx_agent_experience_query',
    'SELECT 1');
PREPARE index_stmt FROM @index_sql;
EXECUTE index_stmt;
DEALLOCATE PREPARE index_stmt;

-- 6) 无论是否发生改名，都重新写入注释，便于修复历史乱码。
ALTER TABLE ai_agent_experience
    MODIFY COLUMN agent_type VARCHAR(32) NOT NULL
        COMMENT '智能体类型：MAIN 主智能体 / ANALYSIS 分析智能体 / SQL_GENERATE SQL 生成 / SQL_INSPECT 结果校验',
    MODIFY COLUMN evidence_refs JSON DEFAULT NULL
        COMMENT '经验依据的智能体流水编号（ai_agent_trace.id）列表';
