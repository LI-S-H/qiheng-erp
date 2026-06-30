-- MVP 系统异常记录表
-- 数据库：MySQL 8
-- 说明：用于工作台“系统异常”聚合待办和后续异常中心。

CREATE TABLE IF NOT EXISTS system_exception (
    id BIGINT NOT NULL COMMENT '系统异常记录ID',
    exception_no VARCHAR(64) NOT NULL COMMENT '稳定异常编号，例如 AI-MCP-20260701-001',
    exception_type VARCHAR(64) NOT NULL COMMENT '异常类型：MCP_TOOL_FAILED、MQ_DEAD_LETTER、EXT_CALLBACK_FAILED、JOB_FAILED、COMPENSATION_FAILED',
    source_module VARCHAR(64) NOT NULL COMMENT '来源模块：AI、MQ、LOGISTICS、SCHEDULER等',
    source_no VARCHAR(100) DEFAULT NULL COMMENT '来源业务单号、消息ID、任务编码或第三方回调幂等键',
    severity VARCHAR(16) NOT NULL DEFAULT 'MEDIUM' COMMENT '严重级别：HIGH、MEDIUM、LOW',
    error_code VARCHAR(100) DEFAULT NULL COMMENT '错误码',
    error_message VARCHAR(1000) NOT NULL COMMENT '错误信息',
    detail_summary VARCHAR(1000) NOT NULL COMMENT '工作台可展示的问题摘要',
    resolve_hint VARCHAR(1000) DEFAULT NULL COMMENT '处理建议',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING、PROCESSING、RESOLVED、IGNORED',
    occurred_at DATETIME NOT NULL COMMENT '异常发生时间',
    last_retry_at DATETIME DEFAULT NULL COMMENT '最近重试时间',
    handled_by BIGINT DEFAULT NULL COMMENT '处理人ID',
    handled_at DATETIME DEFAULT NULL COMMENT '处理时间',
    handle_remark VARCHAR(500) DEFAULT NULL COMMENT '处理备注',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_system_exception_no (exception_no),
    KEY idx_system_exception_status_severity (status, severity, occurred_at),
    KEY idx_system_exception_type_status (exception_type, status),
    KEY idx_system_exception_source (source_module, source_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='系统异常记录表';
