-- 工具调用 ID 可达 192 字符，报告消息键还包含运行 ID 和类型前缀。
ALTER TABLE ai_message MODIFY COLUMN message_key VARCHAR(320) NULL COMMENT '会话内消息幂等标识';
ALTER TABLE ai_agent_trace MODIFY COLUMN task_id VARCHAR(192) NOT NULL COMMENT '任务 ID';
