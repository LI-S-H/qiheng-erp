# 供应商评分改造计划

> 本文合并自原《供应商评分改造：模块化交付计划》《供应商评分改造第 1 期详细实施方案》。
> 评分的**库表口径权威**是 [采购库表设计](database/mvp-purchase-schema.md)，剩余事项与验收状态见 [当前未完成事项](project-open-items.md)。
> 实施过程、真实测试结果与审阅清单见 [评分实施与审阅记录](supplier-score-review.md)。

## 1. 目标与执行规则

本计划替代“先完整做前端、再完整做后端”的拆分方式。后续每一期都以一个可验收的业务模块为单位，必须在同一期完成：数据模型、后端接口与事务、前端页面/适配、Mock 与接口测试、相关联模块回归。

不允许页面先长期依赖 Mock、后端另期开工；也不允许后端先改字段、页面继续提交旧字段。只有当前模块的真实接口、真实页面与测试闭环通过，才进入下一期。

评分存储和计算的固定规则：

- 数据库评分统一使用 `INT × 100`；API 传输与页面展示使用 0–100；`NULL` 表示未就绪，不能用 0 代替。
- 供应商交付分只在 `supplier` 维护；质量、价格、推荐分只在 `supplier_product` 维护。
- 初始服务分和初始报价必须填写原因，但只保存为当前原因，不写评分变化日志。
- 初始服务分和初始报价不写评分变化日志；后续服务分实际变化写 `SERVICE` 日志，报价调整在评分引擎接入前只维护当前值，不写 `PRICE` 日志。
- 业务事实在本地事务内可靠落库；同步重算 A1/A2/A3 在业务事务内调用 `recalcForXxx`，事务提交即生效；B1 在事务提交后通过 RocketMQ + 5min 固定窗口由 Consumer 执行；D1/D2 由定时任务直接调用。合并窗口识别靠统一 `batchNo` 与 Lua 比较删除，日志幂等靠 `change_key` 唯一索引，**不引入通用 Outbox 表、独立重算任务表或评分版本字段**。

### 1.1 批次号规则

消息、Redis pending、重算上下文和评分日志统一使用 `batchNo`，格式为 `SC` + 日期 + 五位序号。首个入库事件创建窗口时预分配，同窗合并及重投沿用；同步和每日校正在产生实际变更日志时分配。批次预分配后没有实际评分变化时允许空号，不回退 SC 编号序列。

## 2. 分期交付顺序

### 第 0 期：模型与契约基线（已完成）

交付物：最终表结构、直切迁移、OpenAPI、契约门禁和测试脚本请求体。

验收：数据库字段/约束存在；`npm run check:openapi` 通过；旧 `onTimeRate`、`qualifiedRate`、产品级 `deliveryScore` 不再出现在新契约中。

### 第 1 期：供应商与供货关系管理模块

范围只覆盖供应商资料和 `supplier_product` 供货关系，不做采购订单和自动履约评分。详细方案见本文件第 3 节。

### 第 2 期：采购订单与入库事实模块

范围是采购订单、仓库入库确认之间的事实闭环；本期先保证评分所需的原始事实正确落库。

后端：

- 草稿创建/编辑不接收 `selectedSupplierScore`；审核通过时统一写入供货关系推荐分快照。
- 审核通过后冻结 `expectedArrivalDate`；报价或实时评分变更不回写历史订单。
- `inbound_bill` 新增只读快照字段 `expected_arrival_date DATE NULL`。采购订单审核生成采购入库单，以及部分入库后生成下一张待确认入库单时，都复制已冻结的 `purchase_order.expected_arrival_date`；销售退货、调整入库和手工补录默认为 `NULL`，前端不得编辑该字段。
- 入库确认后正确回写采购明细；整单首次达到完全入库时，使用最后一笔入库确认时间写入 `fully_received_at`，绝不使用 `update_time`。
- 预计到货日期只保存在入库作业主表 `inbound_bill`，不复制到确认后库存事实表 `stock_bill`。交付评分直接读取采购入库作业快照及 `confirmed_at`，不再为承诺日期反查采购单；历史采购入库单通过 `source_type + source_id` 一次性回填。
- 取消接口接收原因和供应商责任标记；仅没有确认入库事实的 `APPROVED` 订单可标记供应商责任取消；部分入库/完全入库一律拒绝取消。

前端：

- 采购订单表单移除评分提交字段，详情仅展示只读的审核快照。
- 取消弹窗增加取消原因和责任选择，并按状态禁用不允许的选项。
- 订单详情显示完全入库时间、取消责任和取消人。

验收：创建→提交→审核→多次部分入库→完全入库链路；最后确认时间准确；审核前后推荐分快照边界正确；取消边界和并发版本冲突测试通过。

### 第 3 期：供应商评分计算模块

范围是评分服务本身及其同步/异步触发链，不新增 AI 自动下单。

后端：

- 实现统一 `SupplierScoreRecalculateService`、规则版本和 180 天窗口配置；计算过程使用 `BigDecimal` 保证精度，评分最终统一转换成 `INT × 100` 后落库，不存小数分。
- **不新增评分版本字段**（即不在 `supplier` 增加 `score_input_version` / `score_calculated_version`），也不新增评分重算任务表。重算去重靠 Redis 5 分钟固定窗口 + `batchNo` + Lua 比较删除 pending，幂等靠 `supplier_score_change_log.change_key` 唯一索引。
- 触发场景只保留 6 个真实存在的场景：A1 服务分人工调整、A2 单个报价调整、A3 产品参考采购价修改、B1 首次完全入库、D1 报价过期扫描、D2 每日兜底核对。A 类在业务事务内同步重算；B1 在事务提交后经 RocketMQ 5 分钟固定窗口合并；D1/D2 由定时任务持供应商锁直接执行。部分入库不即时重算，由 D2 校正。
- **不实现**入库纠正/冲销触发（当前业务无此功能）、供应商责任取消触发（字段保留记录但不影响评分）、承诺日/逾期档位边界定时任务（180 天统计已自然覆盖过期订单）。
- 产品质量分只纳入近 180 天内已完全入库的采购订单，以确认入库明细的实际单价计算 `合格金额 /（合格金额 + 不合格金额）× 100`。供应商质量分以其全部供货产品的合格、不合格金额总和计算同一比率，金额已自然加权；`score_basis_amount` 是历史累计入库金额，仅用于供应商价格分加权，不参与质量分。
- 供应商交付分按采购金额计算，不和产品绑定。承诺日期按业务时区的自然日解释，`confirmed_at` 先转为业务日期再比较；在 `当前业务日期 <= expected_arrival_date` 时订单仍是观察态，已到和未到部分都暂不进入交付分分母。承诺日当天确认均视为按时。
- 交付分公式固定为 `max(0, 100 × (1 - Σ(受罚金额 × 逾期系数) / Σ到期应交金额))`。逾期系数：按时 0%、1–3 天 25%、4–7 天 50%、8–15 天 75%、超过 15 天 100%。
- 价格分按产品参考采购价与有效报价计算：`price_score = min(10000, reference_price / quoted_price × 10000)`（INT×100）。报价为空、报价过期、报价单价为零或供货关系停用时 `price_score = NULL`。同产品最低有效报价只用于候选展示/同分排序，不作为评分公式基准。
- 推荐分、供应商综合分按 30% 价格 + 30% 交付 + 30% 质量 + 10% 服务计算；必需输入缺失则 `NULL / NOT_READY`，不重分配权重。
- A1/A2/A3 分别由 `recalcForServiceScoreChange(supplierId, beforeServiceScore, reason)`、`recalcForQuoteChange(supplierProductId)`、`recalcForProductReferencePriceChange(productId)` 处理，不共享按范围分支的核心流程。B1 与 D2 使用完整事实重算入口，并复用日志与持久化辅助方法。
- 同步场景（A1/A2/A3）在业务事务内调用对应入口。B1 由首次完全入库事务提交后投递 MQ，经 5 分钟固定窗口合并后重算；D1/D2 由定时任务直接执行，D2 全量校正质量、交付和价格。
- A3 产品参考价修改通过反转接口 `ProductReferencePriceChangeHandler` 在 `erp-product` 的同一事务内调用 `erp-purchase`，不走 Feign 或 MQ。
- MQ 消息 Key 使用 `batchNo` 供批次检索，不依赖 Key 保证幂等或同供应商消息顺序；供应商分布式锁负责消费串行化，同一 `batchNo` 识别合并窗口并关联最终评分日志。
- 5min 固定窗口合并（不滑动）：同 supplier 首事件通过 `BillNoGenerator` 生成统一 SC 批次号，写入 Redis `supplier:score:pending:{id}`，再用专用 Template 的 `syncSend` 投递 `delayLevel=9`（5min）延迟消息。后续事件合并来源，不重新取号、不重置时间。Consumer 在供应商锁内核对 `batchNo`，评分事务成功后才通过 Lua 比较并删除 pending；失败保留供 MQ 重投。
- 日志写入由 `SupplierScoreChangeLogService.appendBatch(ScoreChangeBatchCommand)` 统一处理，各评分入口复用同一日志构造；`change_key = SHA-256(batch_no + supplierId + metricType + supplierProductId)`，依赖 DB UNIQUE 索引保证 MQ 重投/重试下日志不重复。
- 日志写入条数规则：每个 `(batch_no, metric_type, supplier_product_id)` 组合一条；产品维度指标（PRICE/QUALITY）填产品推荐分前后值，供应商维度指标（SERVICE/DELIVERY）的产品推荐分前后值为 NULL。
- `trigger_type` 枚举固定 6 个：`PRICE_TRIGGER` / `SERVICE_TRIGGER` / `INBOUND_TRIGGER` / `QUOTE_EXPIRED_TRIGGER` / `DAILY_TRIGGER` / `MERGED`（合并时使用）。具体业务事件不再单独成枚举，细节写日志 `reason` 字段。
- 第 3 期若补充 `source_ref_type`，候选值为 `MANUAL` / `QUOTE` / `PRODUCT` / `INBOUND` / `QUOTE_EXPIRED` / `DAILY`；落库前必须同步 DDL、实体和 OpenAPI，当前日志尚无此字段。
- D1 报价过期扫描：每日 01:30 执行 `cron "0 30 1 * * ?"`，分页扫描前一日到期且启用的供货关系，按 supplierId 去重，持供应商锁同步重算价格。漏跑由 D2 全量校正补偿。
- D2 每日兜底：每日 02:00 执行 `cron "0 0 2 * * ?"`，分页扫所有 `status = 1` 的 supplier，逐供应商持外层锁直接调用 `recalcFactsForSupplier`，不投递 MQ 消息；有实际变更日志时生成统一 SC 批次号。

前端：

- 供应商和供货关系页面展示评分、样本金额、状态与计算说明；评分变化后刷新关联列表和详情。
- 评分日志只保留一个 `GET /purchase/score-change-logs` 分页查询接口；`supplierId`、`supplierProductId` 均可选，同时传入按 AND 筛选。页面复用一套查询界面，支持按 `metricType` / `triggerType` / `batchNo` / `startTime` / `endTime` 筛选；展示指标分、可空的产品推荐分和供应商总分前后值、`batchNo`、`ruleVersion`、关联业务编号及原因。
- `serviceScoreReason` / `quotedPriceReason` 仍只展示当前值，不与历史日志混淆。

验收：A1/A2/A3 同步路径返回最新评分；B1 采用五分钟固定合并窗口，实际消费可能因重试或积压延后，不能承诺最长五分钟；D1/D2 由定时任务直接持锁重算；MQ 重投/重试下日志不重复；同窗来源完整保留，Lua 比较删除正确；完全入库合并仍使用 `INBOUND_TRIGGER`，合并来源写入日志原因；无样本 / 部分到货未到期 / 各逾期档位 / 完全入库 / 报价过期 / 参考价变更 / 180 天出窗等场景评分正确；业务事实事务回滚时 MQ 消息不发送；评分更新和同批次日志在消费事务中保持一致。

### 第 4 期：采购选供应商模块

范围是将已计算评分用于采购选择与解释。

后端：

- 实现产品候选供应商接口，只返回启用、未删除、`READY`、有效报价的关系。
- 固定排序为推荐分降序、报价升序、样本金额降序、平均到货周期升序、关系 ID 升序。
- 采购审核再次校验选中的供货关系仍属于供应商和产品；审核后保存评分与价格相关快照。

前端：

- 采购明细按产品加载候选供应商，不再全量拉取后本地过滤。
- 展示推荐分、报价、样本金额、平均到货周期及各分项解释；`NOT_READY` 或报价过期关系不可选。
- 草稿可看到当前候选分；详情区分“当前推荐分”和“审核时快照分”。

验收：候选硬过滤、稳定排序、同分排序、报价失效、停用关系、不同供应商/产品关联校验，以及审核后分数变化不回写历史采购单。

### 第 5 期：工作台与 AI 查询模块

范围是消费评分结果，不重新计算评分。

后端：

- 工作台仅返回启用且 `READY` 的供应商表现，使用新评分、样本金额和平均到货周期。
- AI 受控 Tool 只读取候选和解释数据，不能直接修改评分或绕过 `SupplierScoreService`。

前端：

- 工作台移除准时率，改为新评分状态和样本说明。
- AI 工作台/采购建议展示候选依据和不可推荐原因。

验收：工作台无样本状态、权限过滤、AI 查询解释、候选与采购页面结果一致；不引入 Redis 快照依赖。

### 第 6 期：边界任务与运行保障模块

范围是日切边界、失败恢复和长期一致性。

- D1 报价过期扫描：每日 01:30 扫描前一日到期的供货关系，按供应商去重并持锁直接重算；漏跑由 D2 补偿。
- D2 每日兜底核对：每日 02:00 分页扫所有启用供应商，逐供应商持锁直接重算质量、交付、价格和衍生分，并校正质量金额缓存。
- 不实现独立的承诺日/逾期档位边界扫描任务：交付分公式按 180 天统计窗口自然覆盖过期订单，新订单一旦进入下一日由 D2 兜底触发即可。
- 不实现入库纠正/冲销定时任务：当前业务无此功能。
- RocketMQ 消费者按供应商加 Redisson 分布式锁 `supplier:score:lock:{supplierId}`，执行重算；计算或日志事务失败保留 pending 并抛异常触发 MQ 重试，超过重试能力时由 D2 兜底并记录错误日志。
- 重算幂等：日志表 `supplier_score_change_log.change_key` 唯一索引保证 MQ 重投/Consumer 重试下日志不重复；同一 supplier 短时间内多次事件经 Redis 5min 窗口合并后只执行一次重算。
- Redis 状态丢失后的补偿：同 supplier 短时间内可能出现多次重算（每次都开新窗口），但因 `change_key` 幂等不会产生重复日志；D2 每日兜底可作为最终一致性保障。
- 定时任务与 MQ Consumer 共享完整事实重算入口；A1/A2/A3 保持各自独立业务路径。

验收：报价过期扫描按 supplierId 分组合并；每日兜底全量 supplier 都能被处理；MQ 重试/重投幂等；任务并发互斥；跨日到期评分自然反映在 180 天统计中；Redis 宕机后系统仍能最终一致。

## 3. 第 1 期详细方案：供应商与供货关系管理

### 3.1 本期要达成什么

本期交付的不是完整评分公式，而是一个可安全使用的“供应商与供货关系管理模块”。直接切换后的数据库已经禁止旧字段继续使用，因此本期首先将所有管理、查询、采购编辑和工作台读取路径切换至新字段与新契约，避免出现“页面看起来可用、实际访问数据库报未知列”的情况。

| 旧状态 | 本期完成后的状态 |
|---|---|
| 新增、编辑供应商时，前端可任意传综合、交付、质量、价格、服务、准时率和合格率。 | 普通新增、编辑仅维护基础资料；新增时可选填初始服务分和原因。自动分全部只读。 |
| 供货关系中存在产品级交付分，并可由前端编辑全部分数与最近采购价。 | 产品级交付分完全移除；普通维护只维护关系条件；新增时可选填初始报价、有效期、原因。 |
| 服务分、报价与基础资料混在一个保存接口中。 | 服务分使用专用接口，报价使用专用接口；两者均要求原因和乐观锁版本。 |
| 缺少评分数据时，页面与工作台默认显示 `0`。 | 自动分为 `null`，状态为 `NOT_READY`，页面显示“暂无样本/未就绪”；真实零分仍显示 `0.00`。 |
| 采购草稿、编辑由前端提交 `selectedSupplierScore`。 | 草稿、编辑请求不再提交该字段；第 2 期在审核时由后端冻结当时推荐分，可为 `null`。 |

本期不实现质量、价格、交付、推荐和综合分的公式，不实施入库触发、完全入库时间、取消责任、定时重算或候选供应商排序。本期任何新建或仅人工维护的数据都不得伪造自动评分：自动分保持 `null`、`scoreBasisAmount=0`、`scoreStatus=NOT_READY`。

### 3.2 固定数据语义与状态机

- 数据库评分统一为 `INT × 100`；例如页面输入 `86.25`，校验最多两位小数后保存 `8625`。前端和 OpenAPI 使用 0–100 业务值。
- `null` 不等于 `0`：`null` 表示评分条件或样本不足，`0` 表示已完成计算且结果为零。
- `supplier.deliveryScore` 是供应商级交付分，不能出现在 `supplier_product`。
- `supplier_product.qualityScore`、`priceScore`、`aiScore` 都是系统字段；本期不计算、不允许客户端写入。
- `serviceScoreReason` 和 `quotedPriceReason` 是“当前值的原因”，不是历史日志。清空服务分或报价后，这两个字段都保存空字符串，以满足数据库约束。
- 初始服务分、初始报价是建档值：原因必须保存，但绝不写 `supplier_score_change_log`。
- 后续服务分调整若服务分确实变化，才写一条 `SERVICE` 日志；本期还没有自动公式，因此该日志的产品推荐分、供应商总分前后值允许均为 `null`。仅修改原因、分数未变，不写日志。
- 后续报价调整在本期只更新报价与当前原因。价格分尚未计算，前后价格分仍为 `null`，因此不写评分变化日志；第 3 期接入评分引擎后，报价导致价格分实际变化时才写 `PRICE` 日志。

本期服务分调整逻辑如下：

```text
校验 version、分值范围和两位小数、非空原因
读取 supplier（未删除）
if 新服务分与现存 service_score 相同:
    只更新 service_score_reason、维护人和 version
    不写评分日志
else:
    更新 service_score（或清空为 NULL）、service_score_reason、维护人和 version
    写一条 SERVICE 日志：
        metricScoreBefore/After = 原服务分/新服务分
        productRecommendScoreBefore/After = NULL/NULL
        supplierOverallScoreBefore/After = NULL/NULL
        supplierProductId = NULL
        triggerType = SERVICE，reason = 本次操作原因
提交事务
```

报价调整逻辑如下：

```text
校验 version 和 reason
if price、validUntil 都是 NULL:
    quoted_purchase_price = NULL
    quote_valid_until = NULL
    quoted_price_updated_at = NULL
    quoted_price_reason = ''
else:
    校验 price > 0、两位小数、validUntil >= 当前业务日
    写入价格、截止日、当前时间和当前原因
更新维护人和 version
本期 price_score 未计算，故不写评分日志
提交事务
```

这里的“当前业务日”统一从后端 `Clock` 取得，不使用浏览器日期，避免时区或客户端时间被篡改。后续第 3 期评分引擎会接管上述两个入口的重算部分，但不改变本期的数据写入和原因规则。

### 3.3 接口契约与本期实现对齐

#### 3.3.1 供应商接口

| OpenAPI 接口 | 本期实现行为 | 前端动作 |
|---|---|---|
| `GET /purchase/suppliers` | 返回新评分字段、当前服务分原因、样本金额和评分状态；旧准时率、合格率不再返回。 | 列表与详情展示只读分数；`NOT_READY` 显示为空态。 |
| `POST /purchase/suppliers` | 接受基础资料和可选 `serviceScore + serviceScoreReason`；二者必须同时有值或同时为空。初始服务分不写日志。 | 新增弹窗增加“初始服务分”和“建档原因”，默认不填。 |
| `PUT /purchase/suppliers/{supplierId}` | 只接受基础资料与版本号，拒绝所有评分字段。 | 编辑弹窗删除所有评分输入项。 |
| `PUT /purchase/suppliers/{supplierId}/service-score` | 校验 `supplier:manage`、版本、原因、分值精度；按 3.2 规则写服务分和必要日志。 | 详情或行操作打开独立“调整服务分”弹窗。 |
| `PATCH /purchase/suppliers/{supplierId}/status` 与批量接口 | 保留现有启停能力及乐观锁；不修改历史评分、原因或日志。 | 保持现有启停与批量操作。 |
| 删除接口 | 仍因供货关系、采购订单或采购退货引用而拒绝。 | 保持删除错误提示。 |

#### 3.3.2 供货关系接口

| OpenAPI 接口 | 本期实现行为 | 前端动作 |
|---|---|---|
| `GET /purchase/supplier-products` | 返回报价、报价原因、报价更新时间、质量/价格/推荐分、样本金额和状态；不再读取或返回产品级交付分。 | 列表、详情改为展示报价及只读评分；自动分为空时显示“—”。 |
| `POST /purchase/supplier-products` | 供应商和产品必须存在且启用，二元关系唯一；初始报价的价格、截止日、原因必须全有或全无；初始报价不写日志。 | 新增弹窗仅在新建时选择供应商和产品，并提供可选初始报价组。 |
| `PUT /purchase/supplier-products/{supplierProductId}` | 仅接受 `minOrderQty`、`status`、`remark` 和版本；拒绝供应商、产品、产品编码、报价、平均到货周期、最新成交价及自动分。 | 编辑时供应商和产品锁定展示，报价不在该表单修改。 |
| `PUT /purchase/supplier-products/{supplierProductId}/quote` | 校验 `supplier:manage`、版本、报价组合、截止日、原因与金额精度；维护当前报价原因。 | 独立“调整报价”弹窗，支持设置或清空报价；清空时仍填写本次操作原因。 |
| 启停、删除及批量接口 | 保持乐观锁。停用/删除不清理历史分数和日志；第 4 期候选查询再统一排除停用关系。 | 保持既有批量动作，成功后刷新列表。 |

`GET /purchase/supplier-products/candidates` 已写入目标 OpenAPI，但属于第 4 期“采购选供应商”模块。本期不提供伪候选或将 `NOT_READY` 当作可推荐对象；该接口在第 4 期按 `READY`、有效报价、启用状态和固定排序一次完成。

#### 3.3.3 需要先补入 OpenAPI 的缺口

在开始代码前，先补齐下列契约说明和校验；否则 OpenAPI、数据库约束和实际行为会不一致。

1. `serviceScore` 需要标注“最多两位小数”，并在创建、调整两个请求中使用 `multipleOf: 0.01`；当前数据库只能无损保存到分。
2. `quotedPurchasePrice` 同样需要最多两位小数；创建和调整报价必须明确 `quoteValidUntil` 不早于后端当前业务日。数据库只能校验非空组合，不能校验动态日期。
3. `PurchaseSupplierProduct` 的 `quotedPriceReason` 是当前报价原因；清空报价时返回空字符串。专用报价请求中的 `reason` 是本次操作原因，两者在清空场景不能混用。
4. 供货关系分页接口中若仍出现“供应商名称来自 `supplier_product` 冗余快照”的描述，应改为“查询时关联 `supplier` 主数据”。当前新表没有该冗余列。
5. 供应商和供货关系所有自动评分、平均到货周期、样本金额在本期均允许为 `null` 或 `0`（样本金额），页面和 Mock 不得把空值归一成 `0`。

### 3.4 后端改造清单与实施顺序

**步骤 A：先恢复新库可运行性**（目标是让查询和普通 CRUD 不再访问已删除的 `supplier.on_time_rate`、`supplier.qualified_rate`、`supplier_product.delivery_score`）

1. 更新 `Supplier`、`SupplierProduct` 实体：删除旧字段映射；新增 `serviceScoreReason`、`scoreBasisAmount`、`scoreStatus`、报价字段、报价更新时间、报价原因等新表字段映射。
2. 更新供应商、供货关系 DTO 与 VO：拆分创建、基础编辑、服务分调整、报价调整请求对象；所有自动分只在 VO 中出现且为只读。
3. 更新 `SupplierServiceImpl` 与 `SupplierProductServiceImpl`：改造实体构造、查询列、`toVo` 与金额/分数转换。金额统一使用现有金额序列化规则，分数使用 `QtyUtil` 仅在边界转换。
4. 新增评分状态枚举或受控常量，避免散落字符串；本期所有新建记录固定写 `NOT_READY`，不允许管理接口写 `READY`。
5. 普通更新请求即使手工伪造评分、报价、供应商 ID 或产品 ID 字段，也应因 DTO 不接收而不生效；服务端不依赖前端禁用状态保障安全。

**步骤 B：补齐人工服务分、初始报价与报价调整**

1. 新增服务分调整 DTO、Controller 路由、Service 方法和权限校验；该方法自身标注事务。
2. 新增报价调整 DTO、Controller 路由、Service 方法和权限校验；该方法自身标注事务。
3. 新增最小的 `SupplierScoreChangeLog` 实体、Mapper 和写入服务。本期仅供服务分实际变化写 `SERVICE` 日志；禁止创建时调用它。
4. 对服务分、报价、最小起订量分别实施输入精度检查：评分与金额最多两位小数，最小起订量遵循产品 `quantityPrecision`。
5. 乐观锁冲突、已删除对象、无 `supplier:manage` 权限、原因空白、报价三字段不成组、过期新报价均返回明确业务错误，且不得产生部分更新。

**步骤 C：处理被本期表结构直接影响的采购与工作台**（不是提前实现第 2、3、5 期功能）

1. `PurchaseOrderServiceImpl` 及采购明细 DTO 删除对客户端 `selectedSupplierScore` 的校验、映射与写入。创建、编辑采购草稿保留数据库字段为 `NULL`；第 2 期审核时再由后端写快照。
2. 采购单的供货关系校验仍必须校验“关系属于订单供应商且产品一致、供应商/产品/关系均启用”。本期不以推荐分和有效报价过滤人工采购编辑；该约束留给第 4 期候选选择。
3. 采购页面预填单价的优先级调整为“未过期人工报价 → 最近成交价 → 产品参考采购价”，但用户仍可按既有权限编辑单价。此处只是预填，不表示已启用自动推荐。
4. `DashboardSupplierPerformanceLoader`、VO 与工作台类型改为读取新供应商字段，删除准时率；保留 `null`，不再转换为零。工作台本期只做兼容展示：已就绪才显示完整评分，未就绪显示“暂无样本”。
5. AI 模块本期不接入评分查询或候选接口；清除其 Mock 中已删除的准时率字段，保留与供应商评分无关的建议数据，避免前端类型残留旧字段。

**步骤 D：契约、Mock 与前端页面成套切换**

1. 前端 `purchase/types.ts` 拆分供应商/供货关系创建、编辑、服务分、报价 payload，自动评分字段改为可空只读字段，新增 `scoreStatus`、`scoreBasisAmount`、原因和报价时间。
2. 前端 `purchase/api.ts` 增加服务分、报价专用调用和响应归一化；Mock 必须与真实接口字段、空值、初始不记日志规则一致，不能继续保留手填自动分的种子与保存逻辑。
3. `SupplierManageView.vue`：新增服务分/原因初始字段，服务分调整弹窗；删除旧评分输入与准时率/合格率展示；详情展示状态、样本金额、当前服务分原因。
4. `SupplierProductManageView.vue`：新增初始报价组和报价调整弹窗；删除产品级交付分、质量/价格/推荐分输入；编辑时锁定供应商/产品；详情展示报价原因、有效期、自动分和评分状态。
5. `PurchaseOrderManageView.vue`：草稿 payload 删除 `selectedSupplierScore`；展示时对其可空处理，编辑阶段可显示“当前推荐分（仅参考）”但不提交。
6. `DashboardView.vue`、`dashboard/types.ts`、`dashboard/api.ts`：删除准时率依赖，全部使用可空格式化函数，避免 `null.toFixed()` 和“无样本显示 0”。

### 3.5 数据库、事务与日志约束

本期不再新增业务表。第 0 期的 `supplier`、`supplier_product` 和 `supplier_score_change_log` 即可承载本期数据。

- 创建供应商（含初始服务分）及创建供货关系（含初始报价）分别在一个事务中完成。任何原因校验或写入失败均回滚主数据。
- 服务分调整的“分数、当前原因、维护人、版本、评分变化日志”必须在一个事务中。日志失败则服务分不能落库。
- 报价调整的“报价四字段、维护人、版本”必须在一个事务中。本期没有价格分实际变化时不写日志。
- 本期不让 Controller、Vue 或 Mock 计算自动分；评分公式集中进入第 3 期服务。
- 所有查询都必须验证逻辑删除条件；删除和停用不删除评分历史。

### 3.6 测试与验收设计

**后端单元与集成测试**

| 测试场景 | 核心断言 |
|---|---|
| 供应商新建，无初始服务分 | `serviceScore=null`、原因空字符串、全部自动分 `null`、样本金额 `0`、`NOT_READY`、无日志。 |
| 供应商新建，有初始服务分 | 原因必填；`86.25` 存为 `8625`；无评分日志。 |
| 服务分调整 | 值改变写一条 `SERVICE` 日志；值不变仅换原因不写日志；清空分数后原因清空且写正确 before/after。 |
| 供货关系新建，无初始报价 | 报价相关四字段均为空、自动分 `null`、无日志。 |
| 供货关系新建，有初始报价 | 价格/截止日/原因成组，金额按分存储，初始不记日志。 |
| 报价更新或清空 | 过期日期、零价格、缺少原因、半组字段均失败且无部分更新；清空后当前报价原因为空字符串。 |
| 普通编辑防绕过 | 伪造自动分、报价、最近成交价、供应商 ID、产品 ID 均不改变数据库。 |
| 分页与详情 | 不查询已删除列；全部新字段转换、空值和乐观锁版本正确。 |
| 采购草稿、编辑 | 不带 `selectedSupplierScore` 请求成功，明细快照仍为 `null`。 |
| 工作台 | 不再读取 `on_time_rate`，评分为空时返回空值而不是零。 |
| 事务 | 通过注入日志写入失败验证：服务分、原因、版本全部回滚。 |

测试数据直接写入本地 13307 开发库的隔离测试数据或 Testcontainers/测试库；不得依赖已有用户数据的默认评分。

**接口契约与联调测试**

1. 先更新 `docs/api/erp-openapi.yaml` 的缺口，再同步 Swagger 注解、DTO 校验、前端 TypeScript 类型与 Mock。
2. 扩展 `erp-web/scripts/check-openapi.cjs`，检查旧字段不再出现在供应商、供货关系、采购草稿请求和工作台 schema 中，并检查四个专用原因/报价字段。
3. 更新 `erp-server/scripts/run-four-module-api-it.ps1`：覆盖新增供应商、初始服务分、调整服务分、创建供货关系、初始报价、调整/清空报价、采购草稿和工作台查询。
4. 使用真实后端和本地 Docker MySQL 跑前后端联调，至少验证请求体不包含旧字段、响应字段与 OpenAPI 一致、版本冲突可见、403 权限拦截有效。

**前端构建与视觉测试**

1. 编辑前按仓库规则重新运行 `npm run preflight:frontend -- purchase dashboard ai shared`，并阅读输出。
2. 运行 OpenAPI 契约检查、TypeScript/Vite 构建和采购/工作台 Smoke 脚本；补充供应商与供货关系管理页的专用 Smoke。
3. 在真实浏览器分别验证：新增供应商的“未填初始服务分”和“填写服务分但缺原因”状态；服务分调整、原因错误、版本冲突和清空分数；新增/编辑供货关系、初始报价与报价清空；评分未就绪时列表、详情和工作台的空态；采购草稿保存不再提交评分快照。
4. 视觉检查要覆盖窄屏、表格横向滚动、两个独立调整弹窗、禁用/加载/错误状态，并保存关键截图作为回归证据。

### 3.7 本期完成判定

只有同时满足以下条件才可进入第 2 期：

1. 新库上供应商、供货关系、采购草稿和工作台接口均不访问已删除列。
2. OpenAPI、Swagger 注解、DTO、实体、VO、前端类型、Mock 与真实响应一致。
3. 自动评分不能由客户端写入；初始原因规则、服务分日志规则和报价清空规则均经集成测试验证。
4. 采购草稿不再传评分快照，工作台不把评分空值显示为零。
5. 后端测试、四模块接口联调、OpenAPI 检查、前端构建、采购/工作台/供应商页面浏览器测试全部通过。

## 4. 入库单预计到货日期快照实施清单

### 4.1 字段归属与数据语义

- 新字段为 `inbound_bill.expected_arrival_date DATE NULL`，表示生成该采购入库作业单时，来源采购订单已经冻结的预计到货日期快照。
- 字段只对系统生成的 `PURCHASE_IN + PURCHASE_ORDER` 有业务值；其他类型保持 `NULL`。它不是仓库人员填写项，也不进入入库单新增/编辑 DTO。
- `confirmed_at` 仍表示该批入库实际确认时间；两者分别是“承诺时间”和“实际到货事实”，不能互相替代。
- 不在 `stock_bill` 增加同名字段。`stock_bill` 是确认后库存变动流水，一张采购订单可能产生多张流水，继续复制承诺日期只会重复数据；评分查询保留并读取已确认的 `inbound_bill` 即可。
- 该快照避免交付评分为了读取承诺日期反查 `purchase_order`，并保证以后即使发生合法的采购单纠正也不会改写既有入库事实。金额、产品和供货关系仍需按来源明细读取，不能把该字段理解为消除所有关联查询。

### 4.2 实施顺序与代码影响

1. 数据库与设计文档：修改 `docs/database/sql/003_mvp_warehouse.sql`、`docs/database/sql/008_mvp_return.sql`、`docs/database/mvp-warehouse-schema.md`、`docs/database/mvp-purchase-schema.md` 和总览中的字段语义；新增独立迁移脚本，为存量表加列、按 `inbound_bill.source_id = purchase_order.id` 回填采购入库历史数据，并增加 `(inbound_type, status, deleted, expected_arrival_date)` 索引以支持到期扫描。`008_mvp_return.sql` 当前给 `SALES_RETURN` 写预计到货日期，必须改为 `NULL`，否则与本字段的采购承诺语义冲突。
2. 接口契约：修改 `docs/api/erp-openapi.yaml`，把 `expectedArrivalDate` 只放入 `InboundBillListItem`，并新增基于该类型扩展的 `InboundBillDetail`/入库成功响应供入库详情与操作接口使用，声明 `format: date + nullable + readOnly`；不加入出库响应、库存流水响应和入库写请求。当前入库接口错误复用通用 `StockBillDetail` 的契约也在这一步拆正。
3. 采购生成链路：修改 `PurchaseOrderServiceImpl.generatePurchaseInboundBill`，每次生成首张或部分入库后的下一张采购入库单时，都从已冻结的采购订单复制 `expectedArrivalDate`。审核入口已有非空校验，不新增前端输入。完全入库时将 `fully_received_at` 改为本次 `bill.getConfirmedAt()`，不能再次调用 `LocalDateTime.now()`；当前同步 `refreshAverageDeliveryDays` 在第 3 期异步评分入口可用后再移除，避免分期实施期间平均周期停止更新。
4. 仓储后端：修改 `InboundBill` 实体、`InboundBillListItemVo`、`InboundBillServiceImpl` 的分页选择和详情转换。由于当前公共 `StockBillDetailVoMapping` 同时服务出库，不把入库专属日期强塞进公共接口；在入库转换完成后单独赋值，避免污染 `OutboundBill`。
5. 前端仓储页：修改 `stock-bills/types.ts`、`api.ts`、Mock 和 `StockBillManageView.vue`。日期只读显示；采购入库列表提供可选“预计到货日期”列，详情固定展示，其他单据显示 `—`；不在新增/编辑弹窗出现。
6. 评分模块：交付重算查询直接按 `inbound_bill.source_party_id`、`expected_arrival_date`、`confirmed_at` 和状态取得承诺及分批到货事实；到期任务只扫描采购入库类型，并按供应商去重后发送重算消息。

交付金额聚合必须防止部分入库重复计数：历史已确认入库单只按各自明细的 `current_qty` 计算该批金额；当前唯一一张待确认续单只按 `plan_qty - processed_qty` 计算尚未到货金额，绝不能把每张分批入库单里重复保存的 `plan_qty` 全部累加。单价仍通过 `inbound_bill_item.source_item_id` 关联采购明细取得，本次新增日期快照只消除承诺日期的采购单头反查，不伪装成完全无关联查询。

### 4.3 必测场景

- 采购订单审核后，首张待确认入库单日期与采购订单完全一致。
- 部分入库确认后，新生成的下一张待确认入库单仍继承同一日期；已确认旧入库单日期不变。
- 完全入库不再生成下一张待确认单，最后一张的 `confirmed_at` 正确写入，同时采购单 `fully_received_at` 等于最后一次确认时间。
- 销售退货、调整入库和手工补录入库的日期为空，且所有写接口都无法伪造或修改该快照。
- 历史回填后，所有具备有效采购来源的采购入库单均与来源采购单日期一致；无有效来源的数据保持 `NULL` 并列入数据核对报告。
- 从空库按编号完整执行 `003 → 008 → 009` 等初始化 SQL，确认不存在“后续脚本引用尚未创建字段”的顺序错误。
- OpenAPI 契约检查、采购/仓储跨模块集成测试、前端构建、真实接口联调和入库列表/详情浏览器测试全部通过。

## 5. 每期统一完成条件

每一期必须同时满足以下条件：

1. 数据库、OpenAPI、后端 DTO/VO、前端类型和页面字段完全一致。
2. 后端单元测试、跨模块集成测试、前端契约检查、构建和对应浏览器测试通过。
3. Mock 只服务开发态，并与真实接口字段一致；真实接口可用后必须完成真实联调。
4. 无旧字段、旧接口写入方式或旧公式残留；不通过兼容层双写。
5. 输出本期“前后对比、测试结果、未进入本期的风险”后才允许开始下一期。
