# AI 数据数据库结构

依据 2026-11-21 提供的 MySQL 结构导出整理，列类型、默认值和索引以建表文件为准；业务含义及关联仍需与代码逐项核实。

## `ai_agent_experience`

表用途：AI节点长期经验表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 经验ID |
| `agent_type` | `varchar(32)` | 否 | — | 智能体类型：MAIN 主智能体 / ANALYSIS 分析智能体 / SQL_GENERATE SQL 生成 / SQL_INSPECT 结果校验 |
| `experience_text` | `text` | 否 | — | 经验正文，高度精简 |
| `source_task_id` | `varchar(192)` | 是 | NULL | 来源任务 ID，用于防重复提取 |
| `status` | `varchar(16)` | 否 | 'CANDIDATE' | 状态：CANDIDATE、PUBLISHED、REVOKED |
| `effective_from` | `datetime` | 是 | NULL | 生效时间 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``KEY `idx_agent_experience_query` (`agent_type`,`status`,`deleted`,`effective_from`)``
- ``KEY `idx_agent_experience_list` (`agent_type`,`status`,`deleted`,`id`)``

## `ai_agent_experience_summary`

表用途：AI 经验目录摘要

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `agent_type` | `varchar(32)` | 否 | — | 智能体类型：MAIN / ANALYSIS |
| `summary` | `text` | 否 | — | 该智能体的经验目录摘要，供模型判断是否检索 |

索引与约束：

- ``PRIMARY KEY (`agent_type`)``

## `ai_agent_trace`

表用途：智能体任务执行流水

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | AUTO_INCREMENT | 流水 ID |
| `user_id` | `bigint` | 否 | — | 所属用户 ID |
| `conversation_id` | `bigint` | 否 | — | 所属会话 ID |
| `task_id` | `varchar(192)` | 否 | — | 任务 ID |
| `agent_type` | `varchar(32)` | 否 | — | 智能体类型：MAIN 主智能体 / ANALYSIS 分析智能体 / SQL_GENERATE SQL 生成 / SQL_INSPECT 结果校验 |
| `event_type` | `varchar(32)` | 否 | — | 事件类型：INPUT / MODEL_OUTPUT / TOOL_CALL / TOOL_RESULT / FINAL / MODEL_FAILURE / ERROR |
| `content` | `json` | 否 | — | 事件内容 |
| `create_time` | `datetime(6)` | 否 | CURRENT_TIMESTAMP(6) | 记录时间 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``KEY `idx_agent_trace_task` (`user_id`,`conversation_id`,`task_id`,`id`)``
- ``KEY `idx_agent_trace_extract` (`agent_type`,`create_time`)``

## `ai_analysis_result`

表用途：AI 分析结果元信息

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `result_id` | `varchar(64)` | 否 | — | — |
| `user_id` | `bigint` | 否 | — | — |
| `conversation_id` | `bigint` | 否 | — | — |
| `analysis_task_id` | `varchar(64)` | 否 | — | — |
| `execution_id` | `varchar(64)` | 否 | — | — |
| `result_type` | `varchar(16)` | 否 | — | — |
| `name` | `varchar(255)` | 否 | — | — |
| `description` | `varchar(500)` | 是 | NULL | — |
| `file_type` | `varchar(32)` | 是 | NULL | — |
| `file_size` | `bigint` | 否 | — | — |
| `storage_file` | `varchar(255)` | 否 | — | — |
| `source_dataset_ids` | `json` | 否 | — | — |
| `created_at` | `datetime(6)` | 否 | — | UTC |
| `expires_at` | `datetime` | 是 | NULL | UTC 过期时刻，按天对齐；NULL 表示不自动过期 |

索引与约束：

- ``PRIMARY KEY (`result_id`)``
- ``KEY `idx_analysis_result_owner` (`user_id`,`conversation_id`,`created_at`)``
- ``KEY `idx_analysis_result_task` (`user_id`,`conversation_id`,`analysis_task_id`,`execution_id`)``
- ``KEY `idx_analysis_result_expires` (`expires_at`)``

## `ai_conversation`

表用途：AI经营助手会话表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 会话ID |
| `user_id` | `bigint` | 否 | — | 所属用户ID，只允许服务端从登录上下文写入 |
| `title` | `varchar(40)` | 否 | '新的经营会话' | 会话标题 |
| `description` | `varchar(200)` | 否 | '尚未开始分析' | 会话摘要 |
| `last_message_at` | `datetime` | 是 | NULL | 最后一条消息时间 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``KEY `idx_ai_conversation_user_list` (`user_id`,`deleted`,`last_message_at`,`id`)``

## `ai_data_table`

表用途：AI可用数据表登记表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 主键ID |
| `table_name` | `varchar(64)` | 否 | — | 物理表名 |
| `business_name` | `varchar(100)` | 否 | — | 业务名称，给模型看 |
| `module_code` | `varchar(64)` | 否 | — | 所属模块编码，对应ERP权限码前缀，如 product 对应 product:query |
| `description` | `varchar(500)` | 否 | '' | 表说明与口径总述，给模型看 |
| `parent_table` | `varchar(64)` | 否 | '' | 父表（明细表指向主表）；非空表示该表是明细表，取数时自动补父表过滤 |
| `index_defs` | `json` | 否 | — | 索引定义数组：[{"name":"索引名","unique":true,"columns":["列名"]}]，结构字段可自动同步 |
| `sort_order` | `int` | 否 | '0' | Schema组装顺序，主表在前 |
| `status` | `tinyint` | 否 | '1' | 开关：1生效，0停用；人工下线某张表时改这里 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_ai_data_table_name` (`table_name`)``
- ``KEY `idx_ai_data_table_module` (`module_code`,`status`,`deleted`,`sort_order`)``

## `ai_data_table_column`

表用途：AI可用数据表列登记表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 主键ID |
| `table_name` | `varchar(64)` | 否 | — | 物理表名 |
| `column_name` | `varchar(64)` | 否 | — | 物理列名 |
| `column_type` | `varchar(64)` | 否 | '' | 列类型，结构字段可自动同步 |
| `nullable` | `tinyint` | 否 | '1' | 是否可空，结构字段可自动同步 |
| `primary_key` | `tinyint` | 否 | '0' | 是否主键，结构字段可自动同步 |
| `unique_key` | `tinyint` | 否 | '0' | 是否唯一键，结构字段可自动同步 |
| `business_name` | `varchar(100)` | 否 | '' | 业务名称，人工维护 |
| `description` | `varchar(500)` | 否 | '' | 口径说明：单位、放大倍数、取值含义，人工维护 |
| `enum_values` | `json` | 否 | — | 枚举样例，如 ["DRAFT","PENDING_APPROVAL"]，人工维护；空数组表示非枚举 |
| `status` | `tinyint` | 否 | '1' | 开关：1=下发给模型，0=不下发；乐观锁、备注、人员痕迹等噪声列保持0 |
| `sort_order` | `int` | 否 | '0' | 列顺序 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_ai_data_table_column` (`table_name`,`column_name`)``
- ``KEY `idx_ai_data_table_column_table` (`table_name`,`status`,`deleted`,`sort_order`)``

## `ai_data_table_relation`

表用途：AI可用数据表关系登记表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 主键ID |
| `from_table` | `varchar(64)` | 否 | — | 来源表 |
| `from_column` | `varchar(64)` | 否 | — | 来源列 |
| `to_table` | `varchar(64)` | 否 | — | 目标表 |
| `to_column` | `varchar(64)` | 否 | — | 目标列 |
| `cardinality` | `varchar(16)` | 否 | 'N:1' | 基数：1:1、N:1、1:N |
| `description` | `varchar(500)` | 否 | '' | 关系说明，给模型看 |
| `status` | `tinyint` | 否 | '1' | 开关：1生效，0停用 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_ai_data_table_relation` (`from_table`,`from_column`,`to_table`,`to_column`)``
- ``KEY `idx_ai_data_table_relation_to` (`to_table`,`to_column`,`deleted`)``

## `ai_dataset`

表用途：AI 查询数据集元信息

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `dataset_id` | `varchar(64)` | 否 | — | — |
| `sql_run_id` | `varchar(128)` | 否 | — | — |
| `attempt` | `int` | 否 | — | — |
| `ready_run_id` | `varchar(128)` | 是 | NULL | — |
| `user_id` | `bigint` | 否 | — | — |
| `conversation_id` | `bigint` | 否 | — | — |
| `description` | `varchar(500)` | 否 | — | — |
| `source_sql` | `text` | 否 | — | — |
| `columns_json` | `text` | 否 | — | — |
| `row_count` | `int` | 否 | — | — |
| `completed` | `tinyint(1)` | 否 | — | — |
| `storage_file` | `varchar(128)` | 否 | — | — |
| `status` | `varchar(16)` | 否 | — | — |
| `expires_at` | `datetime(6)` | 是 | NULL | UTC，空表示不自动过期 |
| `created_at` | `datetime(6)` | 否 | — | UTC |
| `data_description_json` | `json` | 是 | NULL | æ•°æ®å†…å®¹ã€ç²’åº¦ã€å­—æ®µå«ä¹‰åŠé™åˆ¶ |
| `truncation_reason` | `varchar(16)` | 是 | NULL | 截断原因：ROW_LIMIT / PAGE_LIMIT |

索引与约束：

- ``PRIMARY KEY (`dataset_id`)``
- ``UNIQUE KEY `uk_ai_dataset_attempt` (`sql_run_id`,`attempt`)``
- ``UNIQUE KEY `uk_ai_dataset_ready_run` (`ready_run_id`)``
- ``KEY `idx_ai_dataset_owner` (`user_id`,`conversation_id`,`dataset_id`)``
- ``KEY `idx_ai_dataset_expires` (`expires_at`)``

## `ai_message`

表用途：AI经营助手消息表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 消息ID |
| `conversation_id` | `bigint` | 否 | — | 会话ID |
| `user_id` | `bigint` | 否 | — | 所属用户ID，用于所有权校验 |
| `request_id` | `varchar(64)` | 否 | — | 服务端轮次标识；历史数据使用legacy前缀 |
| `seq` | `bigint` | 否 | — | 会话内全局递增消息序号 |
| `message_key` | `varchar(320)` | 是 | NULL | 会话内消息幂等标识 |
| `role` | `varchar(16)` | 否 | — | 消息角色：user、assistant、tool |
| `kind` | `varchar(32)` | 否 | — | 消息种类：USER_INPUT / ASSISTANT_STEP / TOOL_CALL / TOOL_RESULT / ASSISTANT_FINAL / ANALYSIS_STEP / REPORT_STEP |
| `content` | `longtext` | 否 | — | 消息内容 JSON；文本类为 JSON 字符串，工具类为 JSON 对象，按 kind 解释；采购单动作记录保存为 type=PURCHASE_ORDER 的结构化内容，只含动作、状态和单号 |
| `run_status` | `varchar(32)` | 是 | NULL | 轮次状态，仅USER_INPUT使用，不改写消息原文 |
| `error_code` | `varchar(80)` | 是 | NULL | 轮次失败原因编码，仅USER_INPUT使用 |
| `history_summary` | `text` | 是 | — | 最终回复历史导航摘要，未生成或失败为NULL，不替代原文 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_ai_message_sequence` (`conversation_id`,`seq`)``
- ``UNIQUE KEY `uk_ai_message_key` (`conversation_id`,`message_key`)``
- ``KEY `idx_ai_message_user` (`user_id`,`deleted`)``
- ``KEY `idx_ai_message_history` (`conversation_id`,`deleted`,`kind`,`seq`)``
- ``KEY `idx_ai_message_request` (`conversation_id`,`request_id`,`seq`)``
- ``KEY `idx_ai_message_summary_scan` (`kind`,`deleted`,`id`)``
- ``CONSTRAINT `chk_ai_message_content` CHECK (json_valid(`content`))``
- ``CONSTRAINT `chk_ai_message_history_summary` CHECK (((`history_summary` is null) or ((`kind` = _utf8mb4'ASSISTANT_FINAL') and (char_length(`history_summary`) between 1 and 500))))``

## `ai_report`

表用途：AI 助手正式报告

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `report_id` | `varchar(64)` | 否 | — | 服务端生成的报告 ID |
| `user_id` | `bigint` | 否 | — | 报告所属用户 |
| `conversation_id` | `bigint` | 否 | — | 报告所属会话 |
| `request_id` | `varchar(64) COLLATE utf8mb4_bin` | 否 | — | 会话运行轮次 |
| `tool_call_id` | `varchar(192) COLLATE utf8mb4_bin` | 否 | — | 本轮报告工具调用 ID |
| `title` | `varchar(120)` | 否 | — | 同一轮唯一的报告标题 |
| `content` | `longtext` | 否 | — | 审核通过的报告正文和引用 |
| `created_at` | `datetime(6)` | 否 | CURRENT_TIMESTAMP(6) | 报告保存时间 |

索引与约束：

- ``PRIMARY KEY (`report_id`)``
- ``UNIQUE KEY `uk_ai_report_call` (`conversation_id`,`request_id`,`tool_call_id`)``
- ``UNIQUE KEY `uk_ai_report_title` (`conversation_id`,`request_id`,`title`)``
- ``KEY `idx_ai_report_history` (`user_id`,`conversation_id`,`created_at`,`report_id`)``
