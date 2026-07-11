# MVP AI / RAG 库表设计：极简版

## 设计目标

AI 模块先支撑公司知识库 RAG 问答、固定业务 Tool 查询和 AI 调用审计。MVP 阶段不让 AI 直接查 SQL，不让 AI 绕过业务 Service 和权限校验；所有 AI 查询都必须通过受控 Tool，并写入审计日志。

## 简化原则

- MVP 设计 3 张表：`ai_document`、`ai_document_chunk`、`ai_interaction_log`。
- 向量数据不放 MySQL，Embedding 写入 RedisStack；MySQL 只保存文档、切片、向量 key 和审计信息。
- 暂不设计复杂聊天会话表，多轮聊天记录先用 `ai_interaction_log.request_id` 串联。
- 暂不设计 Prompt 模板表，MVP 阶段 Prompt 先放代码或配置文件。
- AI Tool 调用、RAG 问答、后续 Workflow 调用统一写入 `ai_interaction_log`，减少表数量。
- 评分和百分率字段如后续加入，统一遵守 `database-design-conventions.md`：用 `int` 存放大 100 倍后的整数。
- `ai_interaction_log` 是审计日志表，不使用 `deleted`，避免审计记录被软删除影响追溯。

## 表：ai_document（AI 知识库文档表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 文档ID |
| title | varchar(200) | 文档标题 |
| file_name | varchar(255) | 原始文件名 |
| file_ext | varchar(32) | 文件扩展名 |
| storage_path | varchar(500) | 文件存储路径 |
| content_hash | varchar(128) | 文件内容哈希，用于去重 |
| status | varchar(32) | 状态：`UPLOADED`、`PARSED`、`INDEXED`、`FAILED` |
| chunk_count | int | 切片数量 |
| uploaded_by_id | bigint | 上传人ID |
| uploaded_by_name | varchar(100) | 上传人姓名 |
| parsed_at | datetime | 解析完成时间 |
| indexed_at | datetime | 向量索引完成时间 |
| create_time | datetime | 创建时间 |
| update_time | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |
| remark | varchar(500) | 备注 |

关系说明：`ai_document_chunk.document_id` 关联本表。删除文档时需要同步删除 RedisStack 中对应向量索引。

## 表：ai_document_chunk（AI 文档切片表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 切片ID |
| document_id | bigint | 文档ID |
| chunk_index | int | 切片序号 |
| content | mediumtext | 切片内容 |
| content_hash | varchar(128) | 切片内容哈希 |
| token_count | int | token 数估算 |
| vector_key | varchar(200) | RedisStack 向量 key |
| status | varchar(32) | 状态：`PENDING`、`INDEXED`、`FAILED` |
| create_time | datetime | 创建时间 |
| update_time | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |

关系说明：RAG 检索命中后，通过 `vector_key` 和 `id` 反查切片内容，并在回答里返回引用来源。

## 表：ai_interaction_log（AI 交互审计表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 日志ID |
| request_id | varchar(64) | 请求ID，同一次用户请求保持一致 |
| parent_request_id | varchar(64) | 父请求ID，后续 Workflow / Agent 编排时使用 |
| interaction_type | varchar(32) | 交互类型：`RAG_CHAT`、`TOOL_CALL`、`WORKFLOW` |
| user_id | bigint | 用户ID |
| username | varchar(64) | 登录账号 |
| user_question | text | 用户原始问题 |
| tool_name | varchar(100) | Tool 或 Workflow 名称，可为空 |
| permission_code | varchar(128) | 本次调用需要的权限码 |
| permission_passed | tinyint | 权限是否通过：1 通过，0 拒绝 |
| request_params | json | Tool / Workflow 入参 |
| result_summary | text | 返回结果摘要，不保存大量明细 |
| result_count | int | 返回数据条数 |
| cited_chunk_ids | json | RAG 引用的文档切片ID列表 |
| desensitized | tinyint | 是否已脱敏 |
| success | tinyint | 是否成功 |
| error_message | varchar(1000) | 错误信息 |
| duration_ms | int | 调用耗时，毫秒 |
| create_time | datetime | 创建时间 |
| update_time | datetime | 更新时间 |

关系说明：AI Tool 必须记录权限校验结果、入参、返回摘要和是否脱敏。RAG 问答记录引用切片 ID，方便追溯回答来源。

## 表间关系

- `ai_document_chunk.document_id` -> `ai_document.id`
- `ai_interaction_log.cited_chunk_ids` -> `ai_document_chunk.id` 列表，JSON 形式保存

## MVP 业务规则

- 上传文档后先写入 `ai_document`，解析成功后写入 `ai_document_chunk`。
- Embedding 结果写入 RedisStack，`ai_document_chunk.vector_key` 保存 Redis 向量 key。
- RAG 回答必须返回引用来源，引用来源来自 `ai_document_chunk.id`。
- AI Tool 调用必须先校验粗粒度权限码，例如 `ai:query:stock`、`ai:query:sales`、`ai:query:purchase`。
- AI Tool 不直接执行大模型生成 SQL，只能调用已有业务 Service。
- AI Tool 返回大量数据时，日志只保存摘要和条数，不保存完整结果。
- 涉及客户手机号、成本、信用额度等敏感字段时，返回前必须脱敏，并记录 `desensitized = 1`。

## 测试场景

- 可以上传知识库文档并生成文档记录。
- 文档解析后可以生成多个切片记录。
- 切片向量写入 RedisStack 后，`vector_key` 可以反查。
- RAG 问答可以记录 `RAG_CHAT` 交互日志和引用切片。
- AI 查询库存时必须写入 `TOOL_CALL` 审计日志。
- 权限校验失败时也必须写入审计日志，且 `permission_passed = 0`。
- Tool 调用失败时记录 `success = 0` 和错误信息。

## 定时任务数据契约补充

MVP 暂不因此新增角色、商品或仓库快照表。任务配置持久化时应保存稳定 ID 数组：`product_ids`、`warehouse_ids`、`recipient_role_ids`；展示名称在读取时通过对应业务模块查询。`ALL` 表示执行时按当前用户权限展开全部可见资源。

定时任务输出格式只允许 `REPORT`、`CHAT_CARD`。手动执行先创建执行记录并返回异步受理结果（`execution_id`、`task_id`、`RUNNING`、`accepted_at`），最终状态和结果通过执行记录查询。执行结果不再存储兼容字段 `sections`；对话模块的 SSE 流式事件保持不变。
