-- 开发环境一次性切换：先备份并停止 AI 请求；不清空会话或原文。
-- 此脚本针对旧结构执行一次；新库直接使用 006_mvp_ai.sql。
-- MySQL DDL 隐式提交，失败时保留中间状态供核查，禁止整份盲目重跑。
USE erp;

ALTER TABLE ai_conversation ADD COLUMN active_request_id VARCHAR(64) DEFAULT NULL COMMENT '当前执行轮次，用于拒绝过期异步回调' AFTER user_id;
ALTER TABLE ai_message
    MODIFY COLUMN content LONGTEXT NOT NULL COMMENT '转换期间原正文与版本化JSON使用同名列',
    ADD COLUMN request_id VARCHAR(64) DEFAULT NULL COMMENT '服务端轮次标识；历史数据使用legacy前缀' AFTER user_id,
    ADD COLUMN seq BIGINT DEFAULT NULL COMMENT '会话内全局递增消息序号' AFTER request_id,
    ADD COLUMN kind VARCHAR(32) DEFAULT NULL COMMENT 'USER_INPUT、ASSISTANT_STEP、TOOL_RESULT、ASSISTANT_FINAL' AFTER role;

-- 保留原正文的所有字符；旧消息边界未知，不伪造轮次和工具调用。
-- 每个图表成为单独的 chart block，其余扩展在同一 content.metadata 中。
UPDATE ai_message AS m
JOIN (SELECT id, ROW_NUMBER() OVER (PARTITION BY conversation_id ORDER BY create_time, id) AS new_seq FROM ai_message) AS ranked ON ranked.id=m.id
SET m.seq=ranked.new_seq,
    m.request_id=CONCAT('legacy:',m.id),
    m.kind=CASE WHEN m.role='user' THEN 'USER_INPUT' ELSE 'ASSISTANT_FINAL' END,
    m.content=JSON_OBJECT(
        'version',1,
        'blocks',JSON_MERGE_PRESERVE(
            JSON_ARRAY(JSON_OBJECT('type','text','text',m.content)),
            COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('type','chart','spec',c.spec))
                    OVER (ORDER BY c.ord ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING)
                FROM JSON_TABLE(COALESCE(m.charts_json,JSON_ARRAY()), '$[*]' COLUMNS(ord FOR ORDINALITY, spec JSON PATH '$')) AS c
                LIMIT 1),JSON_ARRAY())),
        'metadata',JSON_MERGE_PRESERVE(JSON_OBJECT(),
            IF(m.action_cards_json IS NOT NULL AND JSON_LENGTH(m.action_cards_json)>0,JSON_OBJECT('actionCards',m.action_cards_json),JSON_OBJECT()),
            IF(m.sources_json IS NOT NULL AND JSON_LENGTH(m.sources_json)>0,JSON_OBJECT('sources',m.sources_json),JSON_OBJECT()),
            IF(m.agent_traces_json IS NOT NULL AND JSON_LENGTH(m.agent_traces_json)>0,JSON_OBJECT('agentTraces',m.agent_traces_json),JSON_OBJECT()),
            IF(m.task_card_json IS NOT NULL,JSON_OBJECT('taskCard',m.task_card_json),JSON_OBJECT()),
            IF(m.workbench_json IS NOT NULL,JSON_OBJECT('workbench',m.workbench_json),JSON_OBJECT())));

ALTER TABLE ai_message
    MODIFY COLUMN request_id VARCHAR(64) NOT NULL COMMENT '服务端轮次标识；历史数据使用legacy前缀',
    MODIFY COLUMN seq BIGINT NOT NULL COMMENT '会话内全局递增消息序号',
    MODIFY COLUMN kind VARCHAR(32) NOT NULL COMMENT 'USER_INPUT、ASSISTANT_STEP、TOOL_RESULT、ASSISTANT_FINAL',
    MODIFY COLUMN role VARCHAR(16) NOT NULL COMMENT '消息角色：user、assistant、tool',
    MODIFY COLUMN content JSON NOT NULL COMMENT '版本化原始消息：正文块、图表和工具消息',
    DROP COLUMN charts_json,
    DROP COLUMN action_cards_json,
    DROP COLUMN sources_json,
    DROP COLUMN agent_traces_json,
    DROP COLUMN task_card_json,
    DROP COLUMN workbench_json,
    DROP INDEX idx_ai_message_history,
    ADD UNIQUE INDEX uk_ai_message_sequence (conversation_id,seq),
    ADD INDEX idx_ai_message_history (conversation_id,deleted,kind,seq),
    ADD INDEX idx_ai_message_request (conversation_id,request_id,seq);
