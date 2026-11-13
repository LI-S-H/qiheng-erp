# MVP AI / RAG 库表设计：极简版

## 当前实施阶段：主智能体、会话与取数底座

旧 Planner/Need/Step 主图及其节点记忆写入链路已退出运行；当前主智能体、消息和 SQL 子图设计见 [AI Agent 架构](../ai-agent-architecture.md)。

- 会话 CRUD 和历史分页继续使用 ai_conversation、ai_message；旧规划协议不再展示为 PLAN、NEEDS 或旧追问结构，旧记录保留原文并显示统一提示，不删除历史数据。
- 旧规划启动和恢复入口已删除；当前主智能体通过消息流和追问回答接口运行，会话详情仅返回会话基本信息。
- 智能体执行流水统一写入 `ai_agent_trace`：主智能体、分析智能体、取数子图的两个模型节点各自成行，content 保存原文。
- 节点记忆表 `ai_node_memory` 已由 [038 迁移](sql/038_drop_ai_node_memory.sql) 删除；[037 迁移](sql/037_ai_agent_experience_rename.sql) 把经验表改名为 `ai_agent_experience`，分类字段改为智能体类型。
- 经验提取和分类方案待 Python 侧功能完成后设计；经验表目前只作占位，没有写入链路。
- 受控取数底座的 ai_data_table、ai_data_table_column、ai_data_table_relation 以及 SQL、权限和模型基础能力已接入主智能体。

018、023、026 等历史迁移和 erp.sql 旧导出保持原样，用于追溯或按序初始化；028 在旧迁移之后执行一次，036 至 039 依次执行。仓库脚本更新不表示运行中的数据库已经迁移。

以下未实现的知识库、审计和定时任务内容属于早期设计参考；与现行研究架构冲突时，以架构总入口及对应模块实施时确认的契约为准。

## 设计目标

AI 模块先支撑经营助手的会话、分析与取数。当前不让 AI 直接查 SQL，不让 AI 绕过业务 Service 和权限校验；所有 AI 查询都必须通过受控 Tool。

## 简化原则

- 早期设计的 5 张表是 `ai_document`、`ai_document_chunk`、`ai_interaction_log`、`ai_conversation`、`ai_message`；其中前三张的 RAG 与审计部分未实施，已由 [039 迁移](sql/039_drop_unused_ai_tables.sql) 清理，不计入现行表。
- 经营助手的会话和完整消息分别写入 `ai_conversation`、`ai_message`；智能体执行流水写入 `ai_agent_trace`，不与用户可见消息混用。
- 暂不设计 Prompt 模板表，提示词先放代码或配置文件。
- 评分和百分率字段如后续加入，统一遵守 `database-design-conventions.md`：用 `int` 存放大 100 倍后的整数。
- `ai_agent_trace` 是只追加的流水表，不使用 `deleted`，避免审计记录被软删除影响追溯。

## 已退出：知识库与旧审计表

以下三张表属于早期 MVP 设计，开发库从未建表、代码零引用，由 [039 迁移](sql/039_drop_unused_ai_tables.sql) 统一清理，保留本节仅用于追溯早期设计意图。

### ai_document（AI 知识库文档表）

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

### ai_document_chunk（AI 文档切片表）

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

### ai_interaction_log（AI 交互审计表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 日志ID |
| request_id | varchar(64) | 请求ID，同一次用户请求保持一致 |
| parent_request_id | varchar(64) | 父请求ID |
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
| kind | varchar(32) | `USER_INPUT` / `ASSISTANT_STEP` / `TOOL_CALL` / `TOOL_RESULT` / `ASSISTANT_FINAL` / `ANALYSIS_STEP` / `ERROR` |
| visible | tinyint | 1 为用户历史可见，0 为内部流水；ask_user 的调用和结果可见，其他工具默认不可见 |
| content | longtext + JSON_VALID CHECK | 文本以 JSON 字符串保存，工具调用与结果以 JSON 对象保存；按 kind 解释，不再解析旧规划结构，旧记录原文不改写 |
| run_status / error_code | varchar(32) / varchar(80), nullable | 仅本轮 USER_INPUT 行承载运行状态及失败原因；包括 RUNNING、WAITING_USER、COMPLETED、FAILED、INTERRUPTED、CANCELLED。状态更新不改写正文 |
| history_summary | text, nullable | 只用于 `ASSISTANT_FINAL` 的历史导航摘要；一般150–300 Unicode码点，长文最多500码点，短原文可更短。NULL表示尚未生成或生成失败；不覆盖content、不替代证据原文 |
| create_time / update_time | datetime | 审计时间 |
| deleted | tinyint | 逻辑删除 |

`(conversation_id, seq)` 与 `(conversation_id, message_key)` 为唯一索引；历史分页按 `visible=1` 和 `seq ASC` 读取，使用 `(conversation_id, deleted, visible, seq)` 索引；轮次查询使用 `(conversation_id, request_id, seq)`。内部工具消息不混入普通历史；ask_user 调用和结果可作为追问与用户回答展示。历史消息不改写内容，旧的用户输入与最终回复保持可见。

`ANALYSIS_STEP` 保存一次分析任务的完整过程，`visible=1`：content 为 JSON 对象，含父级工具调用编号 `toolCallId`、目标 `goal`、步骤 `steps`（每步含思路文字 `text` 与工具 `tools`）、完成情况 `completion` 和结论 `summary`；一次任务只写一行，`message_key` 为 `runId:analysis:toolCallId`，重跑同一编号不会重复写入。该行不进入聊天消息流，由分析面板按 `toolCallId` 回看。

每轮先保存用户输入及 `run_status=RUNNING`；按实际形成顺序保存完整助手消息、工具调用与工具结果，不将 SSE Token 分片作为独立消息。追问等待及回答分别更新同一轮状态；正常结束保存最终回复并标记 `COMPLETED`，异常状态如实标记。

旧 Graph MVP 历史记录保留原文但不再按旧协议展示；新 MainAgent 写入按 `030_ai_message_flow.sql` 迁移后的消息流水。正常前端事件和追问接口已接入，断线重连仍待实现。

`content` 保存 Jackson 输出的 JSON 文本。采用 LONGTEXT + JSON_VALID 约束，避免 MySQL 原生 JSON 将高精度小数转换为 double；JSON_EXTRACT 等目录查询仍可直接作用于该文本。不得先把新归档内容 CAST 为 JSON 再存入，否则仍会损失精度。

### 展示、执行与记忆边界（2026-10-27）

- 页面历史通过消息分页读取 MySQL 中的可见消息，包括用户输入、助手文本和 ask_user 的调用与回答；不从检查点重建，不将历史问题当作当前恢复授权。
- 旧规划消息保留数据库原文，但不按旧协议解码或续接。
- 删除会话继续在原会话锁内，以短事务软删除会话及可见消息；不联动原始记忆或经验。
- 原始记忆表与工作窗口表已退出；经验表保留占位。Agent 消息、检查点、执行流水和数据产物分别保存。

## 表：ai_agent_trace（智能体执行流水表）

智能体每次执行的原文都追加到这里，是排障与后续经验提取的事实来源。只追加、不改写、不软删除。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 流水ID，自增即写入顺序 |
| user_id / conversation_id | bigint | 归属用户与会话 |
| task_id | varchar(64) | 任务 ID：主智能体为 runId，分析智能体为分析任务 ID，取数子图为 sqlRunId |
| agent_type | varchar(32) | 智能体类型：`MAIN` 主智能体 / `ANALYSIS` 分析智能体 / `SQL_GENERATE` SQL 生成 / `SQL_INSPECT` 结果校验 |
| event_type | varchar(32) | 事件类型：`INPUT` / `MODEL_OUTPUT` / `TOOL_CALL` / `TOOL_RESULT` / `FINAL` / `MODEL_FAILURE` / `ERROR` |
| content | json | 事件内容；模型原话、工具参数原文和工具返回原文都放在 `text` 字段 |
| create_time | datetime(6) | 记录时间 |

索引 `(user_id, conversation_id, task_id, id)` 支持按一次任务取完整时间线。

**四个智能体各记什么**：

| agent_type | INPUT | MODEL_OUTPUT | 其他 |
|---|---|---|---|
| `MAIN` | 用户输入原文 | 模型这一轮的原话 | `TOOL_CALL` 记工具名与参数原文，`TOOL_RESULT` 记返回原文，`FINAL` 记最终答复原文 |
| `ANALYSIS` | 分析目标原文 | 模型原话 | 同上，另有 `ERROR` 与 `MODEL_FAILURE` |
| `SQL_GENERATE` | 本轮送进模型的输入（需求、上一轮 SQL、收到的反馈、日期与取数上限） | 本轮生成的 SQL 与决定 | 授权范围只记 `schemaFingerprint`，不落 Schema 原文 |
| `SQL_INSPECT` | 本轮送进模型的输入（需求、待校验 SQL、列、行数、样本） | 本轮的检查结论与反馈 | 同上 |

`INPUT` 只记这一轮特有的内容；每次都一样的运行时上下文（授权 Schema、业务口径）不落原文，改记范围指纹。取数子图的交付结果落在 `ai_dataset`，流水不重复保存。

流水写入失败只记录错误日志，不阻断任务本身，因此流水可能不完整。当前不从流水提取经验，也不使用流水恢复图执行。

## 已退役：ai_node_memory、ai_node_working_memory

旧节点记忆链路已退出：[028 迁移](sql/028_retire_ai_node_working_memory.sql) 删除工作窗口表，[038 迁移](sql/038_drop_ai_node_memory.sql) 删除原始记忆表。智能体执行的原文改由 `ai_agent_trace` 承载。

## 表：ai_agent_experience（AI 长期经验表）

由 `ai_node_experience` 改名而来，分类字段从旧节点编码改为智能体类型。本轮不实现经验提炼、发布或检索工作流，表中暂无数据。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 经验ID |
| agent_type | varchar(32) | 智能体类型：`MAIN` / `ANALYSIS` / `SQL_GENERATE` / `SQL_INSPECT`，与 `ai_agent_trace.agent_type` 同一套编码 |
| experience_text | text | 经验正文，高度精简 |
| evidence_refs | JSON, nullable | 经验依据的智能体流水编号（`ai_agent_trace.id`）列表 |
| status | varchar(16) | CANDIDATE、PUBLISHED、REVOKED |
| effective_from | datetime, nullable | 生效时间 |
| create_time / update_time / deleted | datetime / datetime / tinyint | 审计与逻辑删除 |

索引 `(agent_type, status, deleted, effective_from)` 支持按智能体读取已发布经验。

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

- `ai_conversation.user_id` -> `sys_user.id`，弱外键
- `ai_message.conversation_id` -> `ai_conversation.id`，由 Service 事务维护级联逻辑删除
- `ai_message.user_id` -> `sys_user.id`，冗余保存用于归属过滤
- `ai_agent_trace.conversation_id` -> `ai_conversation.id`，弱外键
- `ai_agent_experience.evidence_refs` -> `ai_agent_trace.id` 列表，JSON 形式保存
- `ai_data_table_column.table_name` -> `ai_data_table.table_name`，弱外键
- `ai_data_table_relation` 的两端表名分别指向 `ai_data_table.table_name`，弱外键

## MVP 业务规则

- AI Tool 调用先校验统一入口权限 `ai:assistant:use`；页面级权限不等于数据权限，Tool 仍需按所在业务模块校验。
- AI Tool 不直接执行大模型生成 SQL，只能调用已有业务 Service。
- AI Tool 返回大量数据时，消息表只保存摘要，完整返回留在 `ai_agent_trace` 流水里。
- 会话和消息接口必须从 Sa-Token 登录上下文取得 `user_id`，请求 DTO 不接收该字段。
- AI 服务未启用时消息发送接口明确返回能力不可用，不得返回固定回复、固定商品或内存历史。
- 涉及客户手机号、成本、信用额度等敏感字段时，返回前必须脱敏。

## 测试场景

- 一次主智能体运行后，`ai_agent_trace` 能按 `agent_type=MAIN` 查到输入、模型原话、工具调用、工具结果和最终答复。
- 一次取数后，`ai_agent_trace` 能分别查到 `SQL_GENERATE` 与 `SQL_INSPECT` 两组输入与产出，生成节点的产出含真实 SQL。
- 模型调用失败时流水记 `MODEL_FAILURE`，用户可见消息里不出现框架的错误原文。

## 定时任务数据契约补充

MVP 暂不因此新增角色、商品或仓库快照表。任务配置持久化时应保存稳定 ID 数组：`product_ids`、`warehouse_ids`、`recipient_role_ids`；展示名称在读取时通过对应业务模块查询。`ALL` 表示执行时按当前用户权限展开全部可见资源。

定时任务输出格式只允许 `REPORT`、`CHAT_CARD`。手动执行先创建执行记录并返回异步受理结果（`execution_id`、`task_id`、`RUNNING`、`accepted_at`），最终状态和结果通过执行记录查询。执行结果不再存储兼容字段 `sections`；对话模块的 SSE 流式事件保持不变。
