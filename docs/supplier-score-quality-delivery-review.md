# 供应商质量、交付评分：本次实施与审阅清单

更新时间：2026-09-26。本文件说明本次已批准的后端改造，不覆盖工作区其他未提交改动。

## 1. 本次边界

- 已实现质量/交付事实查询、计算、评分持久化与变化日志、质量金额 Redis 缓存、每日校正任务。
- 未新增入库事件生产者、消费者或仓库业务调用。`applyCompletedOrder` 本期只提供质量金额缓存增量核心，尚不构成白天产品评分落库闭环；触发与受影响产品重算在你审阅通过后接入。
- 价格、服务分保留已有值。价格参考价接入、供应商价格分汇总、报价过期扫描不在本次范围；缺失价格分时，推荐/综合分继续为 NULL，不编造分数。
- `score_basis_amount` 保留现有历史累加行为，不重新回填，不用于质量分。
- 真实 MySQL 仅 SELECT/EXPLAIN，对账后回滚；未执行评分更新、DDL 或历史数据修复。Redis 测试使用独立负数 ID，只清理测试自建键。

## 2. 数据口径与查询顺序

业务日期 D 使用 `Asia/Shanghai`。两种事实的窗口彼此独立。

| 指标 | 订单样本 | 实际参与计算的事实 |
|---|---|---|
| 质量 | `INBOUND_DONE`，`fully_received_at` 在 `[D-179 00:00, D+1 00:00)` | 该订单所有 CONFIRMED 采购入库批次；提前确认的批次不再次按日期截断 |
| 交付 | `APPROVED/PARTIAL_INBOUND/INBOUND_DONE`，`expected_arrival_date` 在 `[D-180, D)` | 全部已确认批次 + 尚未确认交付的剩余金额；没有确认批次也不能漏掉订单 |

每个供应商在一致性 `REPEATABLE_READ` 数据库事务中执行：

1. 按订单 ID 游标读取 200 张候选订单，直至遍历完，不设置总样本固定上限。
2. 按本页订单 ID 批量读取采购明细，仅选择计算需要的列。
3. 按来源订单 ID 批量读取 CONFIRMED、PURCHASE_IN 入库主表，不按入库确认日期截断。
4. 按入库单 ID 批量读取质检数量、来源行、单价快照。
5. 仅质量路径批量读取产品关系归属；包含已逻辑删除关系，避免删除关系改变历史质量事实。交付路径完全不依赖当前产品关系。

在 Java 先按采购单归并入库明细，再按 `source_item_id` 归并批次；不是每张采购单再单独访问数据库。数量核对、来源关系、确认时间或质量单价异常时，整张订单对应样本跳过并告警；不修改异常历史数据。

### 质量计算

```text
批次合格金额原始值 = inbound_bill_item.qualified_qty × unit_price
批次不合格金额原始值 = inbound_bill_item.defective_qty × unit_price
产品质量分 = 产品合格金额 / (产品合格金额 + 产品不合格金额) × 10000
供应商质量分 = 全部有效产品合格金额 / 全部有效产品总金额 × 10000
```

数量存储值是实际数量 ×100，单价是分；共同缩放倍数在评分比值中抵消。乘积与累加使用 `BigInteger`，不逐批舍入，不读取采购 `total_amount` 作为质量金额。供应商直接计算原始金额比值，不平均已经舍入的产品分数。

全合格=10000，全不合格=0，无有效金额=NULL。样本出窗后，产品旧质量分需要显式 SET NULL，不能被 ORM 的默认 NOT_NULL 更新策略保留下来。

### 交付计算

- 每条采购明细 `total_amount` 只计入应交金额一次。
- 每行先累加 `确认批次数量 × 对应逾期百分比`，再加 `剩余未交数量 × 当前逾期百分比`；该加权数量乘明细金额、除以 `采购数量 ×100` 得到罚额。数量严格配平，不重复累加订单金额，也不必排序批次。
- 已确认批次按 `confirmed_at` 相对订单承诺日的天数计罚；剩余未交付金额按 D 计罚。草稿、待确认工作单不代表已交付。
- 逾期系数：按时 0%，1–3 天 25%，4–7 天 50%，8–15 天 75%，超过 15 天 100%。
- 加权罚额保留 `BigDecimal` 亚分精度，评分使用 `penaltyAmountRaw`，不是舍入后的展示罚额。1 分未交订单逾期一天：罚额 0.25 分，交付分 7500。
- 比例除法保留 32 位小数，不提前将批次金额舍入到整数分。1 分订单按时交一半、余下一半逾期 4 天时，罚额同样为 0.25 分，不能把剩余半分舍没。
- `交付分 = max(0, (应交金额 - 罚额) / 应交金额 ×10000)`，最终 HALF_UP；无有效应交金额=NULL。
- 本期不纳入取消责任订单；相关业务字段与触发另行审阅。

## 3. Redis 存什么、如何更新

稳定键：`supplier:score:quality:{supplierId}`，花括号仅表示变量。Hash：

```text
qualifiedTotal     供应商窗口内合格金额原始整数
defectiveTotal     供应商窗口内不合格金额原始整数
businessDate       本次快照业务日期（元数据，不按日期创建键）
ruleVersion        quality-amount-v2
applied:{orderId}  仅当天有效完全入库订单的已纳入标记
```

不保存产品维度 q/d Map，不保存每日金额桶，不保存交付金额。订单标记只是防止“全量快照已包含订单，之后增量又追加”的幂等元数据；不保存 180 天全部订单。

- 全量：持供应商锁后从 DB 汇总，总额与已包含的今日订单标记一起写临时 Hash，设置 24h TTL，再 RENAME 原子替换。每日校正强制使用 DB，不读旧缓存。
- 命中：业务日期、规则版本、两个总额都合法才使用缓存。跨日即视为未命中，窗口出入由当天全量快照校正。
- 白天：仅接受当天首次完全入库订单，查询这一整单的所有确认批次，累计两个总额。不正常重复查询供应商全部历史订单。
- 冷启动：缓存缺失时全量查询并覆盖，重建已包含当前订单，不能“重建后再加一次”；今日基线订单标记也一起重建。
- 并发：全量重建与增量共享供应商 Redisson 锁；金额加法在 Java BigInteger 完成，Lua 只比较字符串和原子写入总额/订单标记，避免浮点精度损失，并检查 PTTL，防止过期后创建永久残缺键。
- 缓存写失败时，已读到的 DB 事实仍可计算；获取评分锁失败则报错等待重试，不绕过锁进行评分写入。
- 旧 `SupplierScoreFactsCacheService` 及其专属测试已删除，无调用的旧数量缓存键方法也已移除；不会复用旧数量缓存。未删除真实 Redis 中已有旧键。

## 4. 重算、落库与任务

`recalcFactsForSupplier`：供应商锁 → 一致性数据库事实 → 产品质量/供应商质量/交付 → 推荐与综合 → 乐观锁更新 → 评分变化日志 → 提交后释放锁。

- 推荐分、综合分仍按 30% 价格 +30% 交付 +30% 质量 +10% 服务；缺任一输入为 NULL，不重新分配权重。
- 价格/服务不在质量交付重算时写回，避免覆盖其独立业务维护结果。
- 质量、推荐、综合分发生变化时均能持久化；指标分未变而衍生分变化也不能被日志过滤条件漏掉。
- `score_status` 与实际分数一起修正，即使分数数值相同也会修复错误的 READY/NOT_READY 状态。
- 版本冲突必须失败回滚，不允许忽略零行更新。评分与日志位于同一 DB 事务。
- D2 每日 02:00 分页处理启用且未删除的供应商，单个失败记录错误但继续处理后续供应商；不依赖新增 MQ 消息。
- `SUPPLIER_SCORE_SCHEDULED_ENABLED` 默认 false，保留开发环境安全开关；你审阅通过后再开启。不在本次真实数据测试中执行会写库的 D2。

## 5. 按什么顺序审阅后端文件

以下 Java 文件均位于 `erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/`，另列公共文件与测试位置。

| 顺序 | 文件 | 本次改动与重点检查 |
|---:|---|---|
| 1 | `service/scoring/SupplierScoreFactsAggregationService.java` | 首先看两个样本查询窗口，再看 loadPage、validate、qualityForOrder、deliveryForOrder。核对分页不截断、全部批次、未交余额、整单异常跳过、亚分罚额与今日基线订单标记 |
| 2 | `mapper/SupplierProductMapper.java` | selectScoringOwners 只选归属字段、按主键批量查；故意不排除逻辑删除记录，只用于历史质量归属 |
| 3 | `domain/supplierscore/dto/FactsSnapshot.java` | 快照改为质量金额、产品金额临时 Map、应交金额、原始罚额及异常计数；不再使用 spAmounts 空缓存或供应商总数量 |
| 4 | `service/scoring/QualityScoreCalculator.java`、`DeliveryScoreCalculator.java`、`AggregateScoreCalculator.java` | 金额比例、零样本 NULL、全不合格 0、亚分罚额、固定权重及最终 HALF_UP |
| 5 | `service/scoring/SupplierQualityAmountCacheService.java` | Hash 字段、24h TTL、原子覆盖包含今日订单、重复增量、Lua PTTL/CAS、大整数；没有产品金额缓存 |
| 6 | `service/scoring/ScoreFactsQueryService.java` | 同一事务读取两种事实、同一锁序列化覆盖与增量、冷启动不重复加、增量只准备缓存，尚未接业务调用 |
| 7 | `service/impl/SupplierScoreRecalculateServiceImpl.java` 与 `service/SupplierScoreRecalculateService.java` | 保留价格/服务、更新全部相关产品质量及推荐、显式写 NULL、状态修复、乐观锁冲突、提交后释放锁 |
| 8 | `service/impl/SupplierScoreChangeLogServiceImpl.java` | 无变化判断同时看指标/推荐/综合，避免只变衍生分时丢日志；原幂等键逻辑保留 |
| 9 | `job/SupplierScoreScheduledJob.java` | D2 按供应商分页、独立事务、单供应商失败继续、上海时区与开关；价格过期任务未接通 |
| 10 | `domain/supplierproduct/entity/SupplierProduct.java`、`service/impl/SupplierProductServiceImpl.java` | 旧 ai_score 不再读写；分页推荐分筛选与 VO aiScore 都映射 recommend_score。API 名称未改变 |
| 11 | 公共 `erp-common/.../constant/SupplierScoreRedisKeys.java`、采购 `domain/supplierscore/constant/SupplierScoreConstants.java` | 新质量键、旧键方法移除、评分日志规则版本 SCORE_V2；不会读取旧缓存假装新事实 |
| 12 | 下述测试与文档 | 按你关注的边界看断言，再核对真实库报告和未实现边界 |

建议每次只审一行对应模块，将“文件 + 方法 + 你的意见”发回来。先审 1–4 的业务口径，再审 5–6 缓存，最后审 7–9 落库/任务；生产触发留到下一轮。

测试文件位于 `erp-server/erp-purchase/src/test/java/com/qiheng/erp/purchase/`：

- `service/scoring/SupplierScoreFactsAggregationServiceTest.java`：多批交付、无确认入库、异常数量、提前批次、采购错误金额不影响质量、201 张跨页、窗口参数、1 分订单的亚分罚额。
- `service/scoring/ScoreFactsQueryServiceTest.java`：强制 DB、缓存命中、全量包含今日订单、冷启动不追加当前订单。
- `service/scoring/SupplierQualityAmountCacheServiceTest.java`：24h TTL、原子 RENAME、大整数、重复订单、全量后迟到增量。
- `service/scoring/SupplierFactsRecalculationTest.java`：质量/推荐落库、价格服务不被覆盖、出窗清空、状态修复、乐观锁冲突。
- `service/scoring/SupplierScoreDailyJobTest.java`：一个供应商失败不阻断下一位。
- `service/scoring/SupplierScoreFactsMysqlIT.java`：真实 13307 mapper 与独立 SQL 对账、EXPLAIN、真实实体映射；仅只读。
- `service/scoring/SupplierQualityAmountRedisIT.java`：真实 6379 TTL、大整数、今日基线/重复事件、过期未命中；清理专用测试键。
- `service/impl/SupplierScoreChangeLogServiceImplTest.java`：指标与衍生分日志过滤、幂等。

同步文档：`docs/database/mvp-purchase-schema.md`、`mvp-warehouse-schema.md`、建库 SQL 003/004、`docs/api/erp-openapi.yaml`。本次不对已有数据库执行这些建库 SQL。

## 6. 真实数据证据与验证边界

13307 的实际规模：20 张采购单、23 条采购明细、25 张入库单、29 条入库明细、17 位未删除供应商。当前有效质量订单 4 张，交付订单 9 张；Java 与独立 SQL 的产品质量金额、供应商质量总额、应交金额和未舍入罚额逐项一致。

样例供应商 `2088323218453118977`（另一个对应样本供应商结果相同）：

- 合格金额原始值 2714800，不合格金额 0；2 张有效质量订单，质量分 100.00。
- 到期应交 35786 分，罚额原始值 4936 分；3 张有效交付订单，交付分 86.21。
- 全部供应商应交金额合计 670852 分，罚额展示合计 609152 分。

历史异常中有完全入库订单缺少完成时间、累计数量与确认批次不一致；测试确认按约定跳过并输出告警，没有回填或假设为未交货。

EXPLAIN 已覆盖生产使用的字段投影与关联查询：订单查询使用现有供应商/窗口索引（小数据下优化器选择可变化），采购明细命中 `idx_purchase_order_item_order`，入库主表命中 `idx_inbound_bill_source`，入库明细命中 `idx_inbound_bill_item_bill`，归属查找使用 PRIMARY。本次不新增索引。各次验证热态每供应商的读取及独立 SQL 对账约 15–74ms；这只证明当前小数据执行正常，不是百万级压测结论。

真实 Redis 测试测得 TTL 86399 秒，大整数精确、重复事件不追加、全量已包含的今日订单不追加、失效键不会从零增量创建；测试键已清理。

最终采购模块共 125 项测试通过（123 项单测 + 2 项真实 MySQL/Redis 集成测试），失败/错误/跳过均为 0；同一 Maven reactor 中的上游模块测试也通过。独立只读复审已通过，发现的基线重复累计、亚分精度、历史删除影响等问题均已修正后复测。

后续无用代码清理后重新执行 clean test：115 项通过（114 项单测 + 1 项真实 MySQL 集成测试），失败、错误、跳过均为 0。移除了旧缓存专属 8 项测试及重复系数方法专属 1 项测试，本轮没有重跑真实 Redis 集成测试。17 位供应商再次对账一致；查询与评分公式未改。公开与重写方法补参数/返回值中文说明，私有辅助方法仅说明作用。

后端整个 reactor 编译通过。前端 OpenAPI 契约检查通过；前端完整构建遇到已有无关错误 `ProductManageView.vue:401`：`ProductListItem` 缺少 `version`。后端打包遇到目标 JAR 无法重命名，未终止既有服务。本次未修改页面布局，也未将静态检查当作浏览器验收。

## 7. 复跑方式

在 `erp-server` 使用 Java21 执行：

```powershell
mvn -pl erp-purchase -am test
mvn -pl erp-admin -am compile

# 数据库密码从环境注入，不写入测试源码或审阅文档。
mvn -pl erp-purchase -am test '-Dtest=SupplierScoreFactsMysqlIT,SupplierQualityAmountRedisIT' '-Dscore.mysql.it=true' '-Dscore.redis.it=true' '-Dsurefire.failIfNoSpecifiedTests=false'
```

MySQL 使用 `ERP_IT_JDBC_PASSWORD`；默认 localhost:13307/erp，可覆盖 `score.it.url`、`score.it.user`。Redis 默认 localhost:6379/0，可覆盖 `score.it.redis.host/port/database`。不要同时启动两轮 Maven 操作同一 target 目录。

在 `erp-web` 执行 `npm run check:openapi`。首次启用定时重算将写入真实评分及日志，需在你审阅确认后明确启用。
