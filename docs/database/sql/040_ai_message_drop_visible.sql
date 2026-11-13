-- 消息表只保留前端要渲染的行：先归位历史不可见工具流水，再删除展示标记。
-- 归位目标为 ai_agent_trace，agent_type 记 MAIN，task_id 沿用原来的轮次标识。
-- 可重复执行：列删除后再次执行只补注释。
-- 显式声明连接字符集，避免客户端默认 latin1 导致中文注释乱码。
SET NAMES utf8mb4;

USE erp;

SET @visible_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_message' AND column_name = 'visible'
);

-- 1) 历史不可见行补进流水表；这些行产生于流水接入之前，流水里没有对应记录。
SET @backfill_sql = IF(@visible_exists > 0,
    'INSERT INTO ai_agent_trace (user_id, conversation_id, task_id, agent_type, event_type, content, create_time)
     SELECT user_id, conversation_id, request_id, ''MAIN'', kind, CAST(content AS JSON), create_time
     FROM ai_message WHERE visible = 0 AND kind IN (''TOOL_CALL'', ''TOOL_RESULT'')',
    'SELECT 1');
PREPARE backfill_stmt FROM @backfill_sql;
EXECUTE backfill_stmt;
DEALLOCATE PREPARE backfill_stmt;

-- 2) 归位后删除消息表里的不可见行，只保留前端可见内容。
SET @cleanup_sql = IF(@visible_exists > 0,
    'DELETE FROM ai_message WHERE visible = 0',
    'SELECT 1');
PREPARE cleanup_stmt FROM @cleanup_sql;
EXECUTE cleanup_stmt;
DEALLOCATE PREPARE cleanup_stmt;

-- 3) 先删除包含该列的索引，否则无法删除列。
SET @visible_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_message' AND index_name = 'idx_ai_message_visible'
);
SET @index_sql = IF(@visible_index_exists > 0,
    'ALTER TABLE ai_message DROP INDEX idx_ai_message_visible',
    'SELECT 1');
PREPARE index_stmt FROM @index_sql;
EXECUTE index_stmt;
DEALLOCATE PREPARE index_stmt;

-- 4) 消息表只存可见内容，展示标记不再需要。
SET @drop_sql = IF(@visible_exists > 0,
    'ALTER TABLE ai_message DROP COLUMN visible',
    'SELECT 1');
PREPARE drop_stmt FROM @drop_sql;
EXECUTE drop_stmt;
DEALLOCATE PREPARE drop_stmt;

-- 5) 补齐消息种类注释：工具调用与追问回答都是前端要渲染的类型。
ALTER TABLE ai_message
    MODIFY COLUMN kind VARCHAR(32) NOT NULL
        COMMENT '消息种类：USER_INPUT / ASSISTANT_STEP / TOOL_CALL / TOOL_RESULT / ASSISTANT_FINAL / ANALYSIS_STEP',
    MODIFY COLUMN content LONGTEXT NOT NULL
        COMMENT '消息内容 JSON；文本类为 JSON 字符串，工具类为 JSON 对象，按 kind 解释';