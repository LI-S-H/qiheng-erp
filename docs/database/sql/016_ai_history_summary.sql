-- 历史导航索引：只添加一列，不覆盖 content，不生成虚构摘要或消息。
-- 执行前备份开发库；DDL 隐式提交。已有 015 结构的开发库执行一次。
USE erp;

ALTER TABLE ai_message
  ADD COLUMN history_summary TEXT NULL COMMENT '最终回复历史导航摘要，未生成或失败为NULL，不替代原文' AFTER content,
  ADD KEY idx_ai_message_summary_scan (kind, deleted, id),
  ADD CONSTRAINT chk_ai_message_history_summary CHECK (history_summary IS NULL OR
    (kind = 'ASSISTANT_FINAL' AND CHAR_LENGTH(history_summary) BETWEEN 1 AND 500));
