# MVP AI / RAG 库表设计：极简版

## 当前实施阶段：主智能体、会话与取数底座

旧 Planner/Need/Step 主图及其节点记忆写入链路已退出运行；当前主智能体、消息和 SQL 子图设计见 [AI Agent 架构](../ai-agent-architecture.md)。

- 会话 CRUD 和历史分页继续使用 ai_conversation、ai_message；旧规划协议不再展示为 PLAN、NEEDS 或旧追问结构，旧记录保留原文并显示统一提示，不删除历史数据。
- 旧规划启动和恢复入口已删除；当前主智能体通过消息流和追问回答接口运行，会话详情仅返回会话基本信息。
- 原始记忆 ai_node_memory 的表结构及 Java 映射保留；本地开发库的旧测试流水已清空，新原文保存方式后续讨论。
- 执行 [028 迁移](sql/028_retire_ai_node_working_memory.sql) 后，旧工作窗口及其测试备份删除；ai_node_experience 保持原名和结构。
- 经验提取和分类方案待 Python 侧功能完成后设计，当前未实现新节点流水采集。
- 受控取数底座的 ai_data_table、ai_data_table_column、ai_data_table_relation 以及 SQL、权限和模型基础能力已接入主智能体。

018、023、026 等历史迁移和 erp.sql 旧导出保持原样，用于追溯或按序初始化；028 在旧迁移之后执行一次。仓库脚本更新不表示运行中的数据库已经迁移。

以下未实现的知识库、审计和定时任务内容属于早期设计参考；与现行研究架构冲突时，以架构总入口及对应模块实施时确认的契约为准。

## 设计目标

AI 模块先支撑公司知识库 RAG 问答、固定业务 Tool 查询和 AI 调用审计。MVP 阶段不让 AI 直接查 SQL，不让 AI 绕过业务 Service 和权限校验；所有 AI 查询都必须通过受控 Tool，并写入审计日志。

## 简化原则

- MVP 设计 5 张表：`ai_document`、`ai_document_chunk`、`ai_interaction_log`、`ai_conversation`、`ai_message`。
- 向量数据不放 MySQL，Embedding 写入 RedisStack；MySQL 只保存文档、切片、向量 key 和审计信息。
- 经营助手的会话和完整消息分别写入 `ai_conversation`、`ai_message`；`ai_interaction_log` 继续只承担模型、Tool 和 Workflow 审计，不能替代历史消息。
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

## 表：ai_conversation（AI 经营助手会话表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 会话ID |
| user_id | bigint | 所属用户ID，只能从服务端登录上下文取得 |
| title / description | varchar | 会话标题与摘要；标题默认「新的经营会话」，用户发出第一条需求后由服务端改为需求前 20 个字 |
| last_message_at | datetime | 最后一条消息时间，用于会话列表排序；每落一条消息同步刷新 |
| create_time / update_time | datetime | 审计时间 |
| deleted / version | tinyint / int | 逻辑删除和乐观锁版本 |

跨用户访问、已删除会话和不存在会话统一按不存在处理，避免通过错误差异枚举其他用户的会话。会话删除在同一事务内先逻辑删除其消息，再逻辑删除会话；项目采用弱外键约定，不创建物理外键。

会话表不增加运行字段。旧规划停用期间，接口保持 `running`、`runNode`、`runLabel`、`runStatus` 字段，返回 `false`、`null`、`null`、`IDLE`，不读取旧检查点推断可恢复状态。新的研究运行状态在后续接入时单独设计。
历史设计中的 `active_request_id` 与四个 `run_*` 列已分别由
`sql/migrate_ai_conversation_drop_active_request_id.sql` 和 `sql/migrate_ai_conversation_drop_run_progress.sql` 删除。

## 表：ai_message（AI 经营助手消息表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 消息ID |
| conversation_id / user_id | bigint | 所属会话和用户，用于关联与归属校验 |
| request_id | varchar(64) | 服务端生成的轮次标识，历史未知边界使用 `legacy:消息ID` |
| seq | bigint | 会话内全局递增序号，逻辑删除后也不复用 |
| message_key | varchar(192), nullable | 会话内完整消息的幂等标识；历史消息可为空 |
| role | varchar(16) | `user` / `assistant` / `tool` |
| kind | varchar(32) | `USER_INPUT` / `ASSISTANT_STEP` / `TOOL_CALL` / `TOOL_RESULT` / `ASSISTANT_FINAL` / `ERROR` |
| visible | tinyint | 1 为用户历史可见，0 为内部流水；ask_user 的调用和结果可见，其他工具默认不可见 |
| content | longtext + JSON_VALID CHECK | 文本以 JSON 字符串保存，工具调用与结果以 JSON 对象保存；按 kind 解释，不再解析旧规划结构，旧记录原文不改写 |
| run_status / error_code | varchar(32) / varchar(80), nullable | 仅本轮 USER_INPUT 行承载运行状态及失败原因；包括 RUNNING、WAITING_USER、COMPLETED、FAILED、INTERRUPTED、CANCELLED。状态更新不改写正文 |
| history_summary | text, nullable | 只用于 `ASSISTANT_FINAL` 的历史导航摘要；一般150–300 Unicode码点，长文最多500码点，短原文可更短。NULL表示尚未生成或生成失败；不覆盖content、不替代证据原文 |
| create_time / update_time | datetime | 审计时间 |
| deleted | tinyint | 逻辑删除 |

`(conversation_id, seq)` 与 `(conversation_id, message_key)` 为唯一索引；历史分页按 `visible=1` 和 `seq ASC` 读取，使用 `(conversation_id, deleted, visible, seq)` 索引；轮次查询使用 `(conversation_id, request_id, seq)`。内部工具消息不混入普通历史；ask_user 调用和结果可作为追问与用户回答展示。历史消息不改写内容，旧的用户输入与最终回复保持可见。

每轮先保存用户输入及 `run_status=RUNNING`；按实际形成顺序保存完整助手消息、工具调用与工具结果，不将 SSE Token 分片作为独立消息。追问等待及回答分别更新同一轮状态；正常结束保存最终回复并标记 `COMPLETED`，异常状态如实标记。

旧 Graph MVP 历史记录保留原文但不再按旧协议展示；新 MainAgent 写入按 `030_ai_message_flow.sql` 迁移后的消息流水。正常前端事件和追问接口已接入，断线重连仍待实现。

`content` 保存 Jackson 输出的 JSON 文本。采用 LONGTEXT + JSON_VALID 约束，避免 MySQL 原生 JSON 将高精度小数转换为 double；JSON_EXTRACT 等目录查询仍可直接作用于该文本。不得先把新归档内容 CAST 为 JSON 再存入，否则仍会损失精度。

### 展示、执行与记忆边界（2026-10-27）

- 页面历史通过消息分页读取 MySQL 中的可见消息，包括用户输入、助手文本和 ask_user 的调用与回答；不从检查点重建，不将历史问题当作当前恢复授权。
- 旧规划消息保留数据库原文，但不按旧协议解码或续接。
- 删除会话继续在原会话锁内，以短事务软删除会话及可见消息；不联动原始记忆或经验。
- 原始记忆结构暂不迁移；工作记忆表退出；经验表暂不调整。Agent 消息、检查点和数据产物分别保存。

## 表：ai_node_memory（AI 节点记忆原文表）

表结构和 Java 映射暂时保留，不与用户可见消息混用。本地开发库旧测试流水已清空；旧节点写入链路已退出，新 Agent 的原始记忆保存方式尚未确定。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 记忆ID |
| conversation_id / user_id | bigint | 归属会话与用户 |
| node_code | varchar(32) | 节点编码：PLANNER、SQL_GENERATE、PYTHON_GENERATE、REPORTER、REFLECTION |
| seq | bigint | 节点内递增序号，只增不改 |
| content | longtext + JSON_VALID | JSON 原文，永不压缩改写；条目类别由其中 `eventType` 字段表示（USER_INPUT、MODEL_OUTPUT、CLARIFICATION_ASKED、CLARIFICATION_ANSWERED、ACCEPTED_PLAN、FORMAT_FEEDBACK、FORMAT_RETRY_EXHAUSTED），不落列 |
| create_time / deleted | datetime / tinyint | 创建时间与逻辑删除 |

`(conversation_id, node_code, seq)` 唯一索引；序号取 `MAX(seq)` 时不过滤 `deleted`，因为逻辑删除行仍占用唯一索引。

## 已退役：ai_node_working_memory

旧节点的可压缩工作窗口已退出设计。028 迁移删除该表及旧测试备份；原始流水表独立保留。

## 表：ai_node_experience（AI 长期经验表）

暂保留原表名及字段。本轮不实现经验提炼、发布或检索工作流；新的经验分类模型后续讨论。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 经验ID |
| node_code | varchar(32) | 暂沿用的历史分类编码；新分类方式后续设计 |
| experience_text | text | 经验正文，高度精简 |
| evidence_refs | JSON, nullable | 可选证据引用 |
| status | varchar(16) | CANDIDATE、PUBLISHED、REVOKED |
| effective_from | datetime, nullable | 生效时间 |
| create_time / update_time / deleted | datetime / datetime / tinyint | 审计与逻辑删除 |

## 表：ai_data_table（AI 可用数据表登记表）

受控取数底座的表清单。AI 生成 SQL 前按当前登录用户的权限码算出可见表，再把这张登记表里的业务语义与库里的真实结构组装成 Schema 文本交给模型。**模型能看到的表就是这一份，没有第二份。**

表清单落库而不是写在配置文件里：ERP 业务表持续增加，写死在配置里意味着每加一张业务表都要改代码发版；落库后新增业务表只需插一行数据。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 主键ID |
| table_name | varchar(64) UK | 物理表名 |
| business_name | varchar(100) | 业务名称，给模型看 |
| module_code | varchar(64) | 所属模块编码，对应 ERP 权限码前缀 |
| description | varchar(500) | 表说明与口径总述，给模型看 |
| parent_table | varchar(64) | 父表（明细表指向主表），取数时自动补父表过滤 |
| index_defs | JSON | 索引定义数组，结构字段可自动同步 |
| sort_order | int | Schema 组装顺序，主表在前 |
| status | tinyint | 开关：1生效，0停用；人工下线某张表时改这里 |
| create_time / update_time / deleted / version | datetime / datetime / tinyint / int | 审计与乐观锁 |

登记表本身就是授权范围：**AI 只能查这里登记的表，不登记就接触不到**，因此表级不设 `enabled` 开关。`status` 是人工下线开关，改成 0 该表即对模型关闭，不必删登记。

## 表：ai_data_table_column（AI 可用数据表列登记表）

列级登记。与表级语义不同：这里的登记表是**表的完整结构跟踪**，库里的每一列都登记（含噪声列），`status` 决定这一列是否下发给模型。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 主键ID |
| table_name / column_name | varchar(64) UK | 物理表名与列名 |
| column_type / nullable / primary_key / unique_key | varchar(64) / tinyint / tinyint / tinyint | 结构字段，可自动同步 |
| business_name | varchar(100) | 业务名称，人工维护 |
| description | varchar(500) | 口径说明：单位、放大倍数、取值含义，人工维护 |
| enum_values | JSON | 枚举样例，人工维护；ERP 状态值是编码，不给真实取值模型只能猜 |
| status | tinyint | 开关：1=下发给模型，0=不下发（乐观锁、备注、人员痕迹等噪声列） |
| sort_order | int | 列顺序 |

三张表的开关字段统一只有 `status` 一个，语义统一为「这条登记当前是否生效」，不存在两个并存表达同一件事的开关。

## 表：ai_data_table_relation（AI 可用数据表关系登记表）

ERP 全部 DDL 没有外键约束，关系无法从库结构推导，只能人工声明。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 主键ID |
| from_table / from_column | varchar(64) UK | 来源表与来源列 |
| to_table / to_column | varchar(64) UK | 目标表与目标列 |
| cardinality | varchar(16) | 基数：1:1、N:1、1:N |
| description | varchar(500) | 关系说明，给模型看 |
| create_time / update_time / deleted / version | datetime / datetime / tinyint / int | 审计与乐观锁 |

刻意不登记两类关系：指向 `sys_user` 的 `*_by_id`（`sys_user` 不对 AI 开放，登记后模型会去 JOIN 不存在的表）；多态弱关联（`inbound_bill.source_id` 靠 `source_type` 决定指向，写不成固定关系）。

## 表间关系

- `ai_document_chunk.document_id` -> `ai_document.id`
- `ai_interaction_log.cited_chunk_ids` -> `ai_document_chunk.id` 列表，JSON 形式保存
- `ai_conversation.user_id` -> `sys_user.id`，弱外键
- `ai_message.conversation_id` -> `ai_conversation.id`，由 Service 事务维护级联逻辑删除
- `ai_message.user_id` -> `sys_user.id`，冗余保存用于归属过滤
- `ai_data_table_column.table_name` -> `ai_data_table.table_name`，弱外键
- `ai_data_table_relation` 的两端表名分别指向 `ai_data_table.table_name`，弱外键

## MVP 业务规则

- 上传文档后先写入 `ai_document`，解析成功后写入 `ai_document_chunk`。
- Embedding 结果写入 RedisStack，`ai_document_chunk.vector_key` 保存 Redis 向量 key。
- RAG 回答必须返回引用来源，引用来源来自 `ai_document_chunk.id`。
- AI Tool 调用先校验统一入口权限 `ai:assistant:use`；页面级权限不等于数据权限，Tool 仍需按所在业务模块校验。
- AI Tool 不直接执行大模型生成 SQL，只能调用已有业务 Service。
- AI Tool 返回大量数据时，日志只保存摘要和条数，不保存完整结果。
- 会话和消息接口必须从 Sa-Token 登录上下文取得 `user_id`，请求 DTO 不接收该字段。
- AI 服务未启用时消息发送接口明确返回能力不可用，不得返回固定回复、固定商品或内存历史。
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
