-- MVP AI / RAG 库表设计：极简版
-- 数据库：MySQL 8
-- 说明：主键由 MyBatis-Plus ASSIGN_ID 生成，因此不使用 AUTO_INCREMENT。

USE erp;

CREATE TABLE IF NOT EXISTS ai_document (
    id BIGINT NOT NULL COMMENT '文档ID',
    title VARCHAR(200) NOT NULL COMMENT '文档标题',
    file_name VARCHAR(255) NOT NULL DEFAULT '' COMMENT '原始文件名',
    file_ext VARCHAR(32) NOT NULL DEFAULT '' COMMENT '文件扩展名',
    storage_path VARCHAR(500) NOT NULL DEFAULT '' COMMENT '文件存储路径',
    content_hash VARCHAR(128) NOT NULL DEFAULT '' COMMENT '文件内容哈希',
    status VARCHAR(32) NOT NULL DEFAULT 'UPLOADED' COMMENT '状态：UPLOADED、PARSED、INDEXED、FAILED',
    chunk_count INT NOT NULL DEFAULT 0 COMMENT '切片数量',
    uploaded_by_id BIGINT DEFAULT NULL COMMENT '上传人ID',
    uploaded_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '上传人姓名',
    parsed_at DATETIME DEFAULT NULL COMMENT '解析完成时间',
    indexed_at DATETIME DEFAULT NULL COMMENT '向量索引完成时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    KEY idx_ai_document_hash (content_hash),
    KEY idx_ai_document_status (status),
    KEY idx_ai_document_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI知识库文档表';

CREATE TABLE IF NOT EXISTS ai_document_chunk (
    id BIGINT NOT NULL COMMENT '切片ID',
    document_id BIGINT NOT NULL COMMENT '文档ID',
    chunk_index INT NOT NULL DEFAULT 0 COMMENT '切片序号',
    content MEDIUMTEXT NOT NULL COMMENT '切片内容',
    content_hash VARCHAR(128) NOT NULL DEFAULT '' COMMENT '切片内容哈希',
    token_count INT NOT NULL DEFAULT 0 COMMENT 'token数估算',
    vector_key VARCHAR(200) NOT NULL DEFAULT '' COMMENT 'RedisStack向量key',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING、INDEXED、FAILED',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_document_chunk (document_id, chunk_index),
    KEY idx_ai_document_chunk_document (document_id),
    KEY idx_ai_document_chunk_vector_key (vector_key),
    KEY idx_ai_document_chunk_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI文档切片表';

CREATE TABLE IF NOT EXISTS ai_interaction_log (
    id BIGINT NOT NULL COMMENT '日志ID',
    request_id VARCHAR(64) NOT NULL COMMENT '请求ID',
    parent_request_id VARCHAR(64) NOT NULL DEFAULT '' COMMENT '父请求ID',
    interaction_type VARCHAR(32) NOT NULL COMMENT '交互类型：RAG_CHAT、TOOL_CALL、WORKFLOW',
    user_id BIGINT DEFAULT NULL COMMENT '用户ID',
    username VARCHAR(64) NOT NULL DEFAULT '' COMMENT '登录账号',
    user_question TEXT COMMENT '用户原始问题',
    tool_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT 'Tool或Workflow名称',
    permission_code VARCHAR(128) NOT NULL DEFAULT '' COMMENT '本次调用需要的权限码',
    permission_passed TINYINT NOT NULL DEFAULT 0 COMMENT '权限是否通过：1通过，0拒绝',
    request_params JSON COMMENT 'Tool或Workflow入参',
    result_summary TEXT COMMENT '返回结果摘要',
    result_count INT NOT NULL DEFAULT 0 COMMENT '返回数据条数',
    cited_chunk_ids JSON COMMENT 'RAG引用的文档切片ID列表',
    desensitized TINYINT NOT NULL DEFAULT 0 COMMENT '是否已脱敏：1是，0否',
    success TINYINT NOT NULL DEFAULT 1 COMMENT '是否成功：1成功，0失败',
    error_message VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '错误信息',
    duration_ms INT NOT NULL DEFAULT 0 COMMENT '调用耗时，毫秒',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    KEY idx_ai_interaction_request (request_id),
    KEY idx_ai_interaction_parent_request (parent_request_id),
    KEY idx_ai_interaction_user (user_id),
    KEY idx_ai_interaction_type (interaction_type),
    KEY idx_ai_interaction_tool (tool_name),
    KEY idx_ai_interaction_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI交互审计表';

CREATE TABLE IF NOT EXISTS ai_conversation (
    id BIGINT NOT NULL COMMENT '会话ID',
    user_id BIGINT NOT NULL COMMENT '所属用户ID，只允许服务端从登录上下文写入',
    title VARCHAR(40) NOT NULL DEFAULT '新的经营会话' COMMENT '会话标题',
    description VARCHAR(200) NOT NULL DEFAULT '尚未开始分析' COMMENT '会话摘要',
    last_message_at DATETIME DEFAULT NULL COMMENT '最后一条消息时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    KEY idx_ai_conversation_user_list (user_id, deleted, last_message_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI经营助手会话表';

CREATE TABLE IF NOT EXISTS ai_message (
    id BIGINT NOT NULL COMMENT '消息ID',
    conversation_id BIGINT NOT NULL COMMENT '会话ID',
    user_id BIGINT NOT NULL COMMENT '所属用户ID，用于所有权校验',
    role VARCHAR(16) NOT NULL COMMENT '消息角色：user、assistant',
    content TEXT NOT NULL COMMENT '消息正文',
    charts_json JSON COMMENT '后端业务 Tool 生成的图表规格',
    action_cards_json JSON COMMENT '后端受控动作卡片，不允许模型直接拼装业务主键',
    workbench_json JSON COMMENT '后端业务 Tool 校验后的只读工作框',
    sources_json JSON COMMENT '本轮数据来源',
    agent_traces_json JSON COMMENT '多智能体协同轨迹',
    task_card_json JSON COMMENT '快捷任务展示卡片',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    PRIMARY KEY (id),
    KEY idx_ai_message_history (conversation_id, deleted, create_time, id),
    KEY idx_ai_message_user (user_id, deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI经营助手消息表';
