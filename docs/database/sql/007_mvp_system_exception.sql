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

-- 系统异常模块种子数据：逐条对应工作台“系统异常”待办证据。
INSERT INTO system_exception (id, exception_no, exception_type, source_module, source_no, severity, error_code, error_message, detail_summary, resolve_hint, status, occurred_at, last_retry_at, handled_by, handled_at, handle_remark, create_time, update_time) VALUES
(1970000000000000001, 'AI-MCP-20260701-001', 'MCP_TOOL_FAILED', 'AI', 'ai-seed-20260701-004', 'HIGH', 'MCP_TIMEOUT', 'AI模块调用库存预测MCP工具超时，未生成补货建议。', 'AI模块调用库存预测MCP工具超时，未生成补货建议，等待后台重试或检查工具连接。', '检查inventory_forecast_mcp连接与超时配置后重试；恢复后重新生成P000027在WH002的补货建议。', 'PENDING', '2026-07-01 09:18:00', NULL, NULL, NULL, NULL, '2026-07-01 09:18:00', '2026-07-01 09:18:00'),
(1970000000000000002, 'DLQ-ORDER-STOCK-00023', 'MQ_DEAD_LETTER', 'MQ', 'DLQ-ORDER-STOCK-00023', 'HIGH', 'MQ_DEAD_LETTER', '订单审核后生成出库任务的消息进入死信队列。', '订单审核后生成出库任务的消息进入死信队列，需由后台消费补偿后恢复流程。', '核对消息幂等键和消费失败原因，补偿消费成功后恢复对应出库流程。', 'PENDING', '2026-07-01 09:05:00', '2026-07-01 09:12:00', NULL, NULL, NULL, '2026-07-01 09:05:00', '2026-07-01 09:12:00'),
(1970000000000000003, 'EXT-CALLBACK-20260701-006', 'EXT_CALLBACK_FAILED', 'LOGISTICS', 'LOGISTICS-CALLBACK-20260701-006', 'MEDIUM', 'CALLBACK_TIMEOUT', '第三方物流回调连续超时，发货状态暂未同步。', '第三方物流回调连续超时，发货状态暂未同步，后台会按回调幂等键重试。', '检查第三方物流接口可用性；按回调幂等键重试并核对发货状态。', 'PROCESSING', '2026-07-01 08:42:00', '2026-07-01 09:00:00', 1900000000000000001, NULL, '已发起自动重试，等待第三方响应。', '2026-07-01 08:42:00', '2026-07-01 09:00:00'),
(1970000000000000004, 'JOB-DASHBOARD-SNAPSHOT', 'JOB_FAILED', 'SCHEDULER', 'dashboard-snapshot', 'MEDIUM', 'JOB_EXECUTION_FAILED', '经营快照定时任务执行失败，本次趋势缓存沿用上一批次数据。', '经营快照定时任务执行失败，本次趋势缓存沿用上一批次数据，等待下一次调度或人工重跑。', '检查dashboard-snapshot任务日志并人工重跑；完成后刷新工作台经营趋势缓存。', 'PENDING', '2026-07-01 07:30:00', NULL, NULL, NULL, NULL, '2026-07-01 07:30:00', '2026-07-01 07:30:00')
ON DUPLICATE KEY UPDATE exception_no = VALUES(exception_no), exception_type = VALUES(exception_type), source_module = VALUES(source_module), source_no = VALUES(source_no), severity = VALUES(severity), error_code = VALUES(error_code), error_message = VALUES(error_message), detail_summary = VALUES(detail_summary), resolve_hint = VALUES(resolve_hint), status = VALUES(status), occurred_at = VALUES(occurred_at), last_retry_at = VALUES(last_retry_at), handled_by = VALUES(handled_by), handled_at = VALUES(handled_at), handle_remark = VALUES(handle_remark), create_time = VALUES(create_time), update_time = VALUES(update_time);
