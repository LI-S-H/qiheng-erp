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

-- AI 模块种子数据：仅引用系统、产品和仓库模块已存在的主数据。
-- ai_scheduled_task 尚无对应 DDL，前端定时任务为运行时 Mock 配置，不在此伪造持久化数据。

INSERT INTO ai_document (id, title, file_name, file_ext, storage_path, content_hash, status, chunk_count, uploaded_by_id, uploaded_by_name, parsed_at, indexed_at, create_time, update_time, deleted, remark, version) VALUES
(1961000000000000001, '仓库库存风险处置手册', 'warehouse-stock-risk-guide.md', 'md', 'seed://ai/warehouse-stock-risk-guide.md', 'seed-ai-doc-warehouse-risk-v1', 'INDEXED', 2, 1900000000000000004, '仓管主管', '2026-06-30 15:20:00', '2026-06-30 15:22:00', '2026-06-30 15:18:00', '2026-06-30 15:22:00', 0, '对应WH001、WH002和WH008的库存风险复核场景。', 0),
(1961000000000000002, '商品主数据与单位说明', 'product-master-data-guide.md', 'md', 'seed://ai/product-master-data-guide.md', 'seed-ai-doc-product-master-v1', 'INDEXED', 2, 1900000000000000001, '系统管理员', '2026-06-30 14:10:00', '2026-06-30 14:12:00', '2026-06-30 14:08:00', '2026-06-30 14:12:00', 0, '包含P000026 A4复印纸、P000027热敏标签纸等产品口径。', 0),
(1961000000000000003, '经营助手数据边界说明', 'ai-assistant-data-boundary.md', 'md', 'seed://ai/assistant-data-boundary.md', 'seed-ai-doc-boundary-v1', 'INDEXED', 1, 1900000000000000001, '系统管理员', '2026-06-29 17:00:00', '2026-06-29 17:03:00', '2026-06-29 16:58:00', '2026-06-29 17:03:00', 0, 'AI只提供分析建议，业务单据必须由人工在业务模块中确认。', 0)
ON DUPLICATE KEY UPDATE title = VALUES(title), file_name = VALUES(file_name), file_ext = VALUES(file_ext), storage_path = VALUES(storage_path), content_hash = VALUES(content_hash), status = VALUES(status), chunk_count = VALUES(chunk_count), uploaded_by_id = VALUES(uploaded_by_id), uploaded_by_name = VALUES(uploaded_by_name), parsed_at = VALUES(parsed_at), indexed_at = VALUES(indexed_at), create_time = VALUES(create_time), update_time = VALUES(update_time), deleted = VALUES(deleted), remark = VALUES(remark), version = VALUES(version);

INSERT INTO ai_document_chunk (id, document_id, chunk_index, content, content_hash, token_count, vector_key, status, create_time, update_time, deleted, version) VALUES
(1962000000000000001, 1961000000000000001, 0, 'WH002华南中心仓的P000027热敏标签纸可用库存为0时，先复核库存余额、在途入库和调拨来源，再由人工创建采购或调拨业务单据。', 'seed-ai-chunk-warehouse-1', 54, 'seed:ai:doc:1961000000000000001:0', 'INDEXED', '2026-06-30 15:20:00', '2026-06-30 15:22:00', 0, 0),
(1962000000000000002, 1961000000000000001, 1, 'WH008南京备货仓的P000043 USB-C扩展坞为停用产品历史库存，AI可以用于历史风险解释，不得建议新增业务单据。', 'seed-ai-chunk-warehouse-2', 52, 'seed:ai:doc:1961000000000000001:1', 'INDEXED', '2026-06-30 15:20:00', '2026-06-30 15:22:00', 0, 0),
(1962000000000000003, 1961000000000000002, 0, 'P000026为A4复印纸，单位为箱；P000027为热敏标签纸，单位为卷。AI建议中的产品编码、名称和单位必须与product表一致。', 'seed-ai-chunk-product-1', 48, 'seed:ai:doc:1961000000000000002:0', 'INDEXED', '2026-06-30 14:10:00', '2026-06-30 14:12:00', 0, 0),
(1962000000000000004, 1961000000000000002, 1, '停用产品P000043和P000044仅可出现在已发生的库存、流水和分析历史中，不能作为新增单据或补货建议的可选产品。', 'seed-ai-chunk-product-2', 48, 'seed:ai:doc:1961000000000000002:1', 'INDEXED', '2026-06-30 14:10:00', '2026-06-30 14:12:00', 0, 0),
(1962000000000000005, 1961000000000000003, 0, 'AI工作框、图表和行动卡均为只读建议。涉及库存、采购和销售的正式操作必须跳转到对应业务模块，由具备权限的用户人工确认。', 'seed-ai-chunk-boundary-1', 50, 'seed:ai:doc:1961000000000000003:0', 'INDEXED', '2026-06-29 17:00:00', '2026-06-29 17:03:00', 0, 0)
ON DUPLICATE KEY UPDATE document_id = VALUES(document_id), chunk_index = VALUES(chunk_index), content = VALUES(content), content_hash = VALUES(content_hash), token_count = VALUES(token_count), vector_key = VALUES(vector_key), status = VALUES(status), create_time = VALUES(create_time), update_time = VALUES(update_time), deleted = VALUES(deleted), version = VALUES(version);

INSERT INTO ai_conversation (id, user_id, title, description, last_message_at, create_time, update_time, deleted, version) VALUES
(1960000000000000001, 1900000000000000001, '今日经营分析', '库存、销量和采购风险协同分析', '2026-07-01 10:18:00', '2026-07-01 10:18:00', '2026-07-01 10:18:00', 0, 0),
(1960000000000000002, 1900000000000000001, '补货建议讨论', '指定商品生成采购建议', '2026-07-01 09:42:18', '2026-07-01 09:42:00', '2026-07-01 09:42:18', 0, 0),
(1960000000000000003, 1900000000000000001, '销量异常复盘', '解释下滑商品和预测需求', '2026-06-30 18:20:00', '2026-06-30 18:20:00', '2026-06-30 18:20:00', 0, 0),
(1960000000000000004, 1900000000000000004, '库存风险巡检', '低库存、锁定库存和调拨建议', '2026-06-30 16:40:00', '2026-06-30 16:40:00', '2026-06-30 16:40:00', 0, 0),
(1960000000000000005, 1900000000000000001, '供应商履约分析', '交期、价格和异常履约复盘', '2026-06-29 15:12:00', '2026-06-29 15:12:00', '2026-06-29 15:12:00', 0, 0),
(1960000000000000006, 1900000000000000001, '周经营复盘', '周销量、采购和库存状态汇总', '2026-06-29 09:05:00', '2026-06-29 09:05:00', '2026-06-29 09:05:00', 0, 0)
ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), title = VALUES(title), description = VALUES(description), last_message_at = VALUES(last_message_at), create_time = VALUES(create_time), update_time = VALUES(update_time), deleted = VALUES(deleted), version = VALUES(version);

INSERT INTO ai_message (id, conversation_id, user_id, role, content, charts_json, action_cards_json, workbench_json, sources_json, agent_traces_json, task_card_json, create_time, update_time, deleted) VALUES
(1963000000000000001, 1960000000000000001, 1900000000000000001, 'assistant', '我是智能经营助手。请选择快捷分析或直接描述经营问题。', NULL, NULL, NULL, JSON_ARRAY(JSON_OBJECT('sourceId','stock-tool','sourceType','TOOL','title','库存余额 Tool')), NULL, NULL, '2026-07-01 10:18:00', '2026-07-01 10:18:00', 0),
(1963000000000000002, 1960000000000000002, 1900000000000000001, 'user', '帮我分析今天需要优先补货的商品。', NULL, NULL, NULL, NULL, NULL, NULL, '2026-07-01 09:42:00', '2026-07-01 09:42:00', 0),
(1963000000000000003, 1960000000000000002, 1900000000000000001, 'assistant', '已结合库存余额和外部采购数据完成补货分析，请在右侧工作框复核建议。', JSON_ARRAY(JSON_OBJECT('chartId','purchase-gap-bar','productId','1920000000000000027','warehouseId','1930000000000000002','gapQty',40)), JSON_ARRAY(JSON_OBJECT('actionId','open-purchase','actionType','NAVIGATE','route','/purchase/orders')), JSON_OBJECT('workbenchType','PURCHASE_DRAFT','lines',JSON_ARRAY(JSON_OBJECT('productId','1920000000000000026','warehouseId','1930000000000000002','suggestedQty',20),JSON_OBJECT('productId','1920000000000000027','warehouseId','1930000000000000002','suggestedQty',40))), JSON_ARRAY(JSON_OBJECT('sourceId','stock-tool','sourceType','TOOL','title','库存余额 Tool'),JSON_OBJECT('sourceId','external-purchase','sourceType','EXTERNAL','title','外部采购数据')), NULL, NULL, '2026-07-01 09:42:18', '2026-07-01 09:42:18', 0),
(1963000000000000004, 1960000000000000003, 1900000000000000001, 'assistant', '销量预测已完成。', JSON_ARRAY(JSON_OBJECT('chartId','sales-forecast-line','productId','1920000000000000026','forecastDays',7)), NULL, NULL, JSON_ARRAY(JSON_OBJECT('sourceId','external-sales','sourceType','EXTERNAL','title','外部销售汇总')), NULL, NULL, '2026-06-30 18:20:00', '2026-06-30 18:20:00', 0),
(1963000000000000005, 1960000000000000004, 1900000000000000004, 'assistant', '已识别跨仓库存缺口，请复核调拨建议。', JSON_ARRAY(JSON_OBJECT('chartId','stock-risk-pie','warehouseId','1930000000000000002','count',11)), JSON_ARRAY(JSON_OBJECT('actionId','open-stock','actionType','NAVIGATE','route','/warehouse/stocks')), JSON_OBJECT('workbenchType','TRANSFER_DRAFT','lines',JSON_ARRAY(JSON_OBJECT('productId','1920000000000000027','warehouseId','1930000000000000001','targetWarehouseId','1930000000000000002','suggestedQty',24))), JSON_ARRAY(JSON_OBJECT('sourceId','stock-tool','sourceType','TOOL','title','库存余额 Tool')), NULL, NULL, '2026-06-30 16:40:00', '2026-06-30 16:40:00', 0),
(1963000000000000006, 1960000000000000005, 1900000000000000001, 'assistant', '供应商履约分析已完成。', NULL, NULL, NULL, JSON_ARRAY(JSON_OBJECT('sourceId','external-supplier','sourceType','EXTERNAL','title','外部供应商履约数据')), NULL, NULL, '2026-06-29 15:12:00', '2026-06-29 15:12:00', 0),
(1963000000000000007, 1960000000000000006, 1900000000000000001, 'assistant', '本周经营复盘已完成。', JSON_ARRAY(JSON_OBJECT('chartId','purchase-gap-bar','productId','1920000000000000027','warehouseId','1930000000000000002','gapQty',40)), NULL, NULL, JSON_ARRAY(JSON_OBJECT('sourceId','stock-tool','sourceType','TOOL','title','库存余额 Tool'),JSON_OBJECT('sourceId','external-sales','sourceType','EXTERNAL','title','外部销售汇总')), NULL, NULL, '2026-06-29 09:05:00', '2026-06-29 09:05:00', 0)
ON DUPLICATE KEY UPDATE conversation_id = VALUES(conversation_id), user_id = VALUES(user_id), role = VALUES(role), content = VALUES(content), charts_json = VALUES(charts_json), action_cards_json = VALUES(action_cards_json), workbench_json = VALUES(workbench_json), sources_json = VALUES(sources_json), agent_traces_json = VALUES(agent_traces_json), task_card_json = VALUES(task_card_json), create_time = VALUES(create_time), update_time = VALUES(update_time), deleted = VALUES(deleted);

INSERT INTO ai_interaction_log (id, request_id, parent_request_id, interaction_type, user_id, username, user_question, tool_name, permission_code, permission_passed, request_params, result_summary, result_count, cited_chunk_ids, desensitized, success, error_message, duration_ms, create_time, update_time) VALUES
(1964000000000000001, 'ai-seed-20260701-001', '', 'RAG_CHAT', 1900000000000000001, 'admin', '帮我分析今天需要优先补货的商品。', 'inventory-analysis-agent', 'warehouse:query', 1, JSON_OBJECT('productIds',JSON_ARRAY('1920000000000000026','1920000000000000027'),'warehouseIds',JSON_ARRAY('1930000000000000002')), '识别P000027在WH002无可用库存，并给出P000026、P000027的只读补货建议。', 2, JSON_ARRAY('1962000000000000001','1962000000000000003'), 0, 1, '', 842, '2026-07-01 09:42:18', '2026-07-01 09:42:18'),
(1964000000000000002, 'ai-seed-20260701-002', 'ai-seed-20260701-001', 'TOOL_CALL', 1900000000000000001, 'admin', NULL, 'warehouse_stock.query', 'warehouse:query', 1, JSON_OBJECT('productId','1920000000000000027','warehouseId','1930000000000000002'), '返回WH002下P000027库存余额，当前可用库存为0。', 1, NULL, 0, 1, '', 136, '2026-07-01 09:42:12', '2026-07-01 09:42:12'),
(1964000000000000003, 'ai-seed-20260630-003', '', 'RAG_CHAT', 1900000000000000004, 'warehouse01', '请复核跨仓库存缺口。', 'inventory-analysis-agent', 'warehouse:query', 1, JSON_OBJECT('productId','1920000000000000027','fromWarehouseId','1930000000000000001','toWarehouseId','1930000000000000002'), '建议从WH001向WH002调拨24卷热敏标签纸，需人工确认。', 1, JSON_ARRAY('1962000000000000001'), 0, 1, '', 768, '2026-06-30 16:40:00', '2026-06-30 16:40:00'),
(1964000000000000004, 'ai-seed-20260701-004', '', 'TOOL_CALL', 1900000000000000001, 'admin', NULL, 'inventory_forecast_mcp', 'ai:ops:suggest', 1, JSON_OBJECT('productId','1920000000000000027','warehouseId','1930000000000000002'), '库存预测工具调用超时，未生成补货建议；对应系统异常AI-MCP-20260701-001。', 0, NULL, 0, 0, 'MCP工具调用超时', 30000, '2026-07-01 09:18:00', '2026-07-01 09:18:00')
ON DUPLICATE KEY UPDATE request_id = VALUES(request_id), parent_request_id = VALUES(parent_request_id), interaction_type = VALUES(interaction_type), user_id = VALUES(user_id), username = VALUES(username), user_question = VALUES(user_question), tool_name = VALUES(tool_name), permission_code = VALUES(permission_code), permission_passed = VALUES(permission_passed), request_params = VALUES(request_params), result_summary = VALUES(result_summary), result_count = VALUES(result_count), cited_chunk_ids = VALUES(cited_chunk_ids), desensitized = VALUES(desensitized), success = VALUES(success), error_message = VALUES(error_message), duration_ms = VALUES(duration_ms), create_time = VALUES(create_time), update_time = VALUES(update_time);
