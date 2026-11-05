-- 主智能体消息流水：已有字段和索引不重复创建。
-- 只有首次新增 visible 时才初始化旧消息的展示范围，避免重复执行改写新消息。
SET @message_key_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_message' AND column_name = 'message_key'
);
SET @message_visible_exists = (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_message' AND column_name = 'visible'
);
SET @message_key_ddl = IF(@message_key_exists = 0,
    'ALTER TABLE ai_message ADD COLUMN message_key VARCHAR(192) NULL COMMENT ''会话内消息幂等标识'' AFTER seq',
    'SELECT 1');
PREPARE message_stmt FROM @message_key_ddl;
EXECUTE message_stmt;
DEALLOCATE PREPARE message_stmt;

SET @message_visible_ddl = IF(@message_visible_exists = 0,
    'ALTER TABLE ai_message ADD COLUMN visible TINYINT NOT NULL DEFAULT 1 COMMENT ''是否在用户历史中展示'' AFTER kind',
    'SELECT 1');
PREPARE message_stmt FROM @message_visible_ddl;
EXECUTE message_stmt;
DEALLOCATE PREPARE message_stmt;

SET @message_key_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_message' AND index_name = 'uk_ai_message_key'
);
SET @message_key_index_ddl = IF(@message_key_index_exists = 0,
    'ALTER TABLE ai_message ADD UNIQUE KEY uk_ai_message_key (conversation_id, message_key)',
    'SELECT 1');
PREPARE message_stmt FROM @message_key_index_ddl;
EXECUTE message_stmt;
DEALLOCATE PREPARE message_stmt;

SET @message_visible_index_exists = (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_message' AND index_name = 'idx_ai_message_visible'
);
SET @message_visible_index_ddl = IF(@message_visible_index_exists = 0,
    'ALTER TABLE ai_message ADD KEY idx_ai_message_visible (conversation_id, deleted, visible, seq)',
    'SELECT 1');
PREPARE message_stmt FROM @message_visible_index_ddl;
EXECUTE message_stmt;
DEALLOCATE PREPARE message_stmt;

SET @message_visible_init = IF(@message_visible_exists = 0,
    'UPDATE ai_message SET visible = CASE WHEN kind IN (''USER_INPUT'', ''ASSISTANT_FINAL'') THEN 1 ELSE 0 END',
    'SELECT 1');
PREPARE message_stmt FROM @message_visible_init;
EXECUTE message_stmt;
DEALLOCATE PREPARE message_stmt;
