-- 智能体流水补全字段口径：主智能体、分析智能体与取数子图的两个模型节点统一入表。
-- 只补充注释与取值说明，不改变列类型、索引与数据；可重复执行。
-- 显式声明连接字符集，避免客户端默认 latin1 导致中文注释乱码。
SET NAMES utf8mb4;

ALTER TABLE ai_agent_trace
    MODIFY COLUMN task_id VARCHAR(64) NOT NULL
        COMMENT '任务 ID：主智能体为 runId，分析智能体为分析任务 ID，取数子图为 sqlRunId',
    MODIFY COLUMN agent_type VARCHAR(32) NOT NULL
        COMMENT '智能体类型：MAIN 主智能体 / ANALYSIS 分析智能体 / SQL_GENERATE SQL 生成 / SQL_INSPECT 结果校验',
    MODIFY COLUMN event_type VARCHAR(32) NOT NULL
        COMMENT '事件类型：INPUT / MODEL_OUTPUT / TOOL_CALL / TOOL_RESULT / FINAL / MODEL_FAILURE / ERROR';
