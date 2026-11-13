-- 智能体内部任务流水；与前端会话消息分开保存。
CREATE TABLE IF NOT EXISTS ai_agent_trace (
    id BIGINT NOT NULL AUTO_INCREMENT COMMENT '流水 ID',
    user_id BIGINT NOT NULL COMMENT '所属用户 ID',
    conversation_id BIGINT NOT NULL COMMENT '所属会话 ID',
    task_id VARCHAR(64) NOT NULL COMMENT '任务 ID',
    agent_type VARCHAR(32) NOT NULL COMMENT '智能体类型',
    event_type VARCHAR(32) NOT NULL COMMENT '事件类型',
    content JSON NOT NULL COMMENT '事件内容',
    create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '记录时间',
    PRIMARY KEY (id),
    KEY idx_agent_trace_task (user_id, conversation_id, task_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体任务执行流水';
