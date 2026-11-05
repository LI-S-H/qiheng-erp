-- 主智能体消息流水：历史内部消息保持不可见；不改写历史内容或删除记录。
ALTER TABLE ai_message
    ADD COLUMN message_key VARCHAR(192) NULL COMMENT '会话内消息幂等标识' AFTER seq,
    ADD COLUMN visible TINYINT NOT NULL DEFAULT 1 COMMENT '是否在用户历史中展示' AFTER kind,
    ADD UNIQUE KEY uk_ai_message_key (conversation_id, message_key),
    ADD KEY idx_ai_message_visible (conversation_id, deleted, visible, seq);

-- 旧历史页仅展示用户输入与助手最终消息；保留原有展示范围。
UPDATE ai_message
SET visible = CASE WHEN kind IN ('USER_INPUT', 'ASSISTANT_FINAL') THEN 1 ELSE 0 END;
