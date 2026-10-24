# MVP AI / RAG 库表设计：极简版

## 当前实施阶段：需求拆解与人工澄清 + 会话管理

当前主图围绕 `PlannerNode`、`NeedCheckNode`、`NeedReviewNode`、`HumanFeedbackNode` 拆解与审阅需求，图状态和暂停检查点用官方 RedisSaver 保存。需求就绪以 `NEEDS_READY` 结束，信息不足时等待人工回答并恢复；SQL/Python 执行器尚未接入主图，架构目标与当前边界见 [AI Agent 架构](../ai-agent-architecture.md)。

会话管理已经接回，但不再是旧的聊天实现：

- `ai_conversation`、`ai_message` 存用户可见的会话与消息，接口挂在 `/ai/assistant/*`（列表、新建、详情、改名、删除、消息分页）。
- 图 State 和检查点负责执行与恢复；活动执行及已有请求状态共同判断是否运行中，会话表不冗余运行字段。页面历史只读取 MySQL 展示流水。
- 展示流水统一使用 `content={type,data}`，新消息 type 为 TEXT、CLARIFICATION、NEEDS、ERROR，PLAN 只兼容历史。需求与澄清在产物定稿拍尝试落库（需求经校验与审阅、澄清随暂停拍），并通过 SSE 推送；当前保存失败仅记日志，事件投影仍可能送达，不得把收到 SSE 等同于已保存。正常收尾只结清轮次状态并发布终态事件，不补写消息（失败兜底除外）。
- 进度通过 SSE 推送（`/ai/graph/stream`、`/ai/graph/resume/stream`），事件体是对象，由消息转换器序列化。事件名固定为 `node-start`、`node-end`、`artifact`、`clarification_required`、`run_finished`、`run_stopped`：`artifact` 在需求定稿拍推送 NEEDS 内容，与消息展示协议同构；人工暂停轮以 `clarification_required` 收尾，载荷携带最新暂停点（`content.data.pauseId`，事件顶层 `checkpointId` 仅作诊断）与澄清卡同构内容；`run_finished` 当前状态为 NEEDS_READY，`run_stopped` 带停止状态与原因，页面在终态后重新拉取 `ai_message`。事件中的 warning 字段为预留，保存失败告警链路尚未完整实现。

本阶段新增了三张节点记忆表（见下），并由迁移脚本删除了会话表上的历史运行时列。向量检索、历史检索 Agent、RAG 和定时任务仍未接入；Planner 接入独立节点工作记忆读取和按阈值压缩，下文相关描述属于既有数据设计及后续参考。

受控取数底座另有三张元数据表（`ai_data_table`、`ai_data_table_column`、`ai_data_table_relation`，见下），登记 AI 可用数据表的清单、列语义与表间关系。表清单落库而非写死在配置里，新增业务表只需插一行数据即可开放，不必改代码发版。当前只登记 20 张面向业务分析的数据表；AI 自身存储表、权限与用户表、系统内部表一律不登记——不在清单里就查不到，不需要再靠开关挡一道。三张表的开关字段统一只有 `status` 一个：该表登记了但当前停用就改成 0，列级的 `status=0` 则表示这一列结构上存在但不下发给模型（乐观锁、备注、人员痕迹等噪声列）。该底座的表结构与种子数据已落库，组装与取数执行部分尚未接入主图。

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

会话表只存业务事实，不增加运行字段。接口返回 `running`、`runNode`、`runLabel`、`runStatus`；只有存在活动执行才算 RUNNING，暂停、结束、失败和中断分别表达。`nextNode` 表示恢复位置，不能单独证明执行器仍活跃。
历史设计中的 `active_request_id` 与四个 `run_*` 列已分别由
`sql/migrate_ai_conversation_drop_active_request_id.sql` 和 `sql/migrate_ai_conversation_drop_run_progress.sql` 删除。

## 表：ai_message（AI 经营助手消息表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 消息ID |
| conversation_id / user_id | bigint | 所属会话和用户，用于关联与归属校验 |
| request_id | varchar(64) | 服务端生成的轮次标识，历史未知边界使用 `legacy:消息ID` |
| seq | bigint | 会话内全局递增序号，逻辑删除后也不复用 |
| role | varchar(16) | `user` / `assistant` / `tool` |
| kind | varchar(32) | `USER_INPUT` / `ASSISTANT_STEP` / `TOOL_RESULT` / `ASSISTANT_FINAL` |
| content | longtext + JSON_VALID CHECK | 用户可见流水保存 `{type,data}`：TEXT/ERROR 的 data 含 text；CLARIFICATION 含 text、questions、threadId、pauseId，不含 plan（恢复标识缺失的历史只读）；NEEDS 含 text、needs、threadId，不保存恢复暂停点。needs 每项含 id、intent、need、depends、condition、evidence_plan 与代码维护的 status；就绪不代表执行完成。PLAN 的 text、plan、threadId 仅兼容历史。用户回答可含 replyToMessageId，answer 仍为题目与回答归并后的字符串。旧 text/metadata.plan 内容读取时兼容转换，不改写旧原文 |
| run_status / error_code | varchar(32) / varchar(80), nullable | 仅 USER_INPUT 行承载轮次状态及失败原因。状态更新不改写 content；legacy/manual 没有轮次状态时保留 NULL |
| history_summary | text, nullable | 只用于 `ASSISTANT_FINAL` 的历史导航摘要；一般150–300 Unicode码点，长文最多500码点，短原文可更短。NULL表示尚未生成或生成失败；不覆盖content、不替代证据原文 |
| create_time / update_time | datetime | 审计时间 |
| deleted | tinyint | 逻辑删除 |

`(conversation_id, seq)` 为唯一索引；历史分页仅返回用户输入和最终回复，按 `seq ASC` 排序，使用 `(conversation_id, deleted, kind, seq)` 索引；轮次查询使用 `(conversation_id, request_id, seq)`。工具中间消息单独保留，但不混入普通聊天分页或总数。生产环境未接入真实 AI Tool 时不得写入示例助手消息或固定业务标识。

每轮先保存用户输入及独立列 `run_status=RUNNING`，助手工具调用先保存后执行；工具结果只保存框架合并后的权威结果，在下一次模型调用前落库；不保存 SSE Token 分片。成功结束时保存最终回复并标记 `COMPLETED`；失败只允许 `RUNNING` 转 `FAILED` / `CANCELLED` / `INTERRUPTED`。

当前 Graph MVP 只写 `USER_INPUT` 与 `ASSISTANT_FINAL` 两类消息，不写工具中间消息；轮次的执行位置由 Redis 检查点表示，不写回会话表。

`content` 保存 Jackson 输出的 JSON 文本。采用 LONGTEXT + JSON_VALID 约束，避免 MySQL 原生 JSON 将高精度小数转换为 double；JSON_EXTRACT 等目录查询仍可直接作用于该文本。不得先把新归档内容 CAST 为 JSON 再存入，否则仍会损失精度。

### 展示、执行与记忆边界（2026-10-16）

- 打开会话按消息 seq 读取 MySQL，按 content.type 还原文字、问题和需求（兼容历史计划）；不从 State 重建多轮页面。
- 正文和结构化展示内容追加保存，旧需求或计划不被新内容覆盖；用户回答新增记录并通过 replyToMessageId 关联问题，旧问题显示为已回答。只有当前人工暂停点允许继续提交，后端在会话锁内校验；前端独立查询当前恢复锚点的能力仍待补齐，不得把历史最后一条澄清无条件视为当前暂停点。
- 一个会话使用一把已有 Redisson 看门狗锁：校验、保存输入、更新 State、执行、收尾在同一执行线程持锁，Human 暂停后释放；重复请求不能替换正在使用的事件通道。
- 用户消息的 run_status 在本轮成功/失败时更新，助手消息不承载运行状态。可见消息写入和会话时间更新采用短事务；模型调用不在数据库长事务中。
- 同会话消息与节点记忆写入通过统一执行入口串行；MAX(seq) 查询覆盖所有消息类型及占号的软删行。删除会话只软删会话与可见消息，不联动节点记忆、工作记忆和经验。
- State 使用 request_id 关联本次 start/resume。Human 消费问题与字符串回答后归档澄清历史，清空本轮问题、planner 输出与需求检查/审阅结果，重置修复预算后回 Planner。
- SSE 使用 artifact、clarification_required 与薄终态事件；不存在承载 message、historySaved、historyWarning 的公开 result 事件。保存失败告警链路仍是待修缺口，不得宣称当前已完整提示，不得自动重调模型或伪装为已保存。
- 内部坏 JSON、格式错误和模型提示词只保存在 State/节点记忆；公开 formatError 兼容字段始终为空。格式错误带反馈回 Planner，耗尽额度才给友好失败提示。
- 节点原文内容统一序列化为合法 JSON，raw_output 字段保留模型错误原文。Planner 读取工作记忆并使用现有 ContextCompressor；只替换工作表，原文不改；压缩失败保留原上下文。
- 原始尝试、过程条目与已通过计划分别标识：澄清提问/回答凭 pauseId 配对，格式耗尽留结局标记，计划定稿只追“通过”标记、正文由同轮模型输出原文承载、读取端按 requestId 反查；当前任务链的输入/澄清/错误不重复注入。长期经验提炼、历史检索 Agent、检查点定期回收不在本轮范围。
- RedisSaver 继续使用官方实现。当前进度总线为单实例内存通道，不声明支持跨实例事件订阅，也不宣称已有 7 天 TTL 或已删除的维护任务。

## 表：ai_node_memory（AI 节点记忆原文表）

只增不改，作为审计与后续重压缩的取材来源。节点私有，不与用户可见消息混用。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 记忆ID |
| conversation_id / user_id | bigint | 归属会话与用户 |
| node_code | varchar(32) | 节点编码：PLANNER、SQL_GENERATE、PYTHON_GENERATE、REPORTER、REFLECTION |
| seq | bigint | 节点内递增序号，只增不改 |
| content | longtext + JSON_VALID | JSON 原文，永不压缩改写；条目类别由其中 `eventType` 字段表示（USER_INPUT、MODEL_OUTPUT、CLARIFICATION_ASKED、CLARIFICATION_ANSWERED、ACCEPTED_PLAN、FORMAT_FEEDBACK、FORMAT_RETRY_EXHAUSTED），不落列 |
| create_time / deleted | datetime / tinyint | 创建时间与逻辑删除 |

`(conversation_id, node_code, seq)` 唯一索引；序号取 `MAX(seq)` 时不过滤 `deleted`，因为逻辑删除行仍占用唯一索引。

## 表：ai_node_working_memory（AI 节点工作记忆表）

节点实际读取的入口，可压缩。行正文是「模型视角的纯文本转写」：一行 = 模型的一条消息，首行标记说明角色。压缩后以摘要和近期消息替换活动工作窗口，使用未占用的新排序号，软删旧窗口但保留来源引用，避免重用唯一键。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 工作记忆ID |
| conversation_id / user_id / node_code | bigint / bigint / varchar(32) | 归属与节点 |
| sort_order | bigint | 节点内排序号，压缩块占被压缩条目的首位 |
| content | longtext | 纯文本转写；一行 = 模型的一条消息，首行为 `[输入]` / `[输出]` / `[摘要]` 标记，正文与真正发给模型的内容一字不差 |
| source_memory_ids | JSON, nullable | 指向对应流水行 ID；压缩摘要条目记录被摘要掉的原文 ID 数组，便于追溯 |
| create_time / update_time / deleted | datetime / datetime / tinyint | 审计与逻辑删除 |

`(conversation_id, node_code, sort_order)` 唯一索引。反馈写入「需要看到它的人」的记忆，而不是「产生它的人」：例如 PlanCheck 的格式错误写入 PLANNER 的记忆。

## 表：ai_node_experience（AI 节点长期经验表）

由多轮运行提炼的跨会话经验，按状态生效，尚未接入提炼任务。

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 经验ID |
| node_code | varchar(32) | 归属节点编码 |
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
