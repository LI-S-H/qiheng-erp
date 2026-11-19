CREATE TABLE IF NOT EXISTS ai_report (
    report_id VARCHAR(64) NOT NULL COMMENT '服务端生成的报告 ID',
    user_id BIGINT NOT NULL COMMENT '报告所属用户',
    conversation_id BIGINT NOT NULL COMMENT '报告所属会话',
    request_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL COMMENT '会话运行轮次',
    tool_call_id VARCHAR(192) COLLATE utf8mb4_bin NOT NULL COMMENT '本轮报告工具调用 ID',
    title VARCHAR(120) NOT NULL COMMENT '同一轮唯一的报告标题',
    content LONGTEXT NOT NULL COMMENT '审核通过的报告正文和引用',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '报告保存时间',
    PRIMARY KEY (report_id),
    UNIQUE KEY uk_ai_report_call (conversation_id, request_id, tool_call_id),
    UNIQUE KEY uk_ai_report_title (conversation_id, request_id, title),
    KEY idx_ai_report_history (user_id, conversation_id, created_at, report_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 助手正式报告';
