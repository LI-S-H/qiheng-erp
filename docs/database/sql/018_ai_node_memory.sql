-- 节点私有记忆：原文表、工作记忆表、长期经验表。
-- 原文表只增不改，用于审计与追溯；工作记忆表是节点实际读取的入口，压缩时只改写它。
-- 长期经验表跨任务沉淀，不与具体会话绑定。
--
-- 原文表 content 是事件 JSON，条目类别不落列，以其中 eventType 字段为唯一权威来源：
-- USER_INPUT、MODEL_OUTPUT、CLARIFICATION_ASKED、CLARIFICATION_ANSWERED、ACCEPTED_PLAN、
-- ACCEPTED_NEEDS、FORMAT_FEEDBACK、FORMAT_RETRY_EXHAUSTED、REVIEW_ISSUES。
-- ACCEPTED_PLAN/ACCEPTED_NEEDS 是定稿“通过”标记（只含 requestId/status），正文由同轮
-- MODEL_OUTPUT 原文承载。
--
-- 工作记忆表 content 是「模型视角的纯文本转写」（2026-10-23 起）：一行 = 模型的一条消息，
-- 首行为 [输入]/[输出]/[摘要] 标记，正文与真正发给模型的内容一字不差，不再是 JSON。
-- 已有库执行 026_ai_node_working_memory_plain_text.sql 升级；新建库建表后同样执行 026。
--
-- 新库直接使用本脚本；已有库执行一次。

USE erp;

CREATE TABLE IF NOT EXISTS ai_node_memory (
    id              BIGINT       NOT NULL COMMENT '记忆ID',
    conversation_id BIGINT       NOT NULL COMMENT '所属会话ID',
    user_id         BIGINT       NOT NULL COMMENT '所属用户ID，仅服务端从登录上下文写入',
    node_code       VARCHAR(32)  NOT NULL COMMENT '节点编码：PLANNER、SQL_GENERATE、PYTHON_GENERATE、REPORTER、REFLECTION',
    seq             BIGINT       NOT NULL COMMENT '节点内递增序号，只增不改',
    content         LONGTEXT     NOT NULL COMMENT 'JSON原文，永不压缩改写；类别由其中eventType字段表示',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_node_memory_seq (conversation_id, node_code, seq),
    KEY idx_ai_node_memory_audit (conversation_id, node_code, deleted, seq),
    CONSTRAINT chk_ai_node_memory_content CHECK (json_valid(content))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI节点记忆原文表（只增不改）';

CREATE TABLE IF NOT EXISTS ai_node_working_memory (
    id                BIGINT       NOT NULL COMMENT '工作记忆ID',
    conversation_id   BIGINT       NOT NULL COMMENT '所属会话ID',
    user_id           BIGINT       NOT NULL COMMENT '所属用户ID',
    node_code         VARCHAR(32)  NOT NULL COMMENT '节点编码',
    sort_order        BIGINT       NOT NULL COMMENT '节点内排序号；窗口压缩后在历史最大号之后写入，避免复用逻辑删除号',
    content           LONGTEXT     NOT NULL COMMENT 'JSON内容；类别由其中eventType字段表示',
    source_memory_ids JSON         NULL COMMENT '压缩摘要条目记录来源原文ID数组，便于追溯',
    create_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted           TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_node_working_order (conversation_id, node_code, sort_order),
    KEY idx_ai_node_working_read (conversation_id, node_code, deleted, sort_order),
    CONSTRAINT chk_ai_node_working_content CHECK (json_valid(content))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI节点工作记忆表（节点读取入口，可压缩）';

CREATE TABLE IF NOT EXISTS ai_node_experience (
    id              BIGINT       NOT NULL COMMENT '经验ID',
    node_code       VARCHAR(32)  NOT NULL COMMENT '归属节点编码',
    experience_text TEXT         NOT NULL COMMENT '经验正文，高度精简',
    evidence_refs   JSON         NULL COMMENT '可选证据引用',
    status          VARCHAR(16)  NOT NULL DEFAULT 'CANDIDATE' COMMENT '状态：CANDIDATE、PUBLISHED、REVOKED',
    effective_from  DATETIME     NULL COMMENT '生效时间',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted         TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    PRIMARY KEY (id),
    KEY idx_ai_node_experience_query (node_code, status, deleted, effective_from)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI节点长期经验表';
