# 供应商评分实施与审阅记录

> 本文合并自原《供应商质量、交付评分：本次实施与审阅清单》《评分注释与真实 MQ 闭环审阅清单》《评分日志来源改造：最终审阅清单》《真实评分测试保留数据与锁修复》《评分重算 RocketMQ 配置与 Topic 初始化》。
> 评分的**口径与分期计划**见 [供应商评分改造计划](supplier-score-plan.md)，**库表设计**见 [采购库表设计](database/mvp-purchase-schema.md)。
> 本文件是实施与验证的证据记录，不是需求文档。

## 1. 质量、交付评分口径与查询顺序

### 1.1 本次边界

- 已实现质量/交付事实查询、计算、评分持久化与变化日志、质量金额 Redis 缓存、每日校正任务。
- 未新增入库事件生产者、消费者或仓库业务调用。`applyCompletedOrder` 只提供质量金额缓存增量核心，尚未构成白天产品评分落库闭环。
- 价格、服务分保留已有值。价格参考价接入、供应商价格分汇总、报价过期扫描不在本次范围；缺失价格分时，推荐/综合分继续为 NULL，不编造分数。
- `score_basis_amount` 保留现有历史累加行为，不重新回填，不用于质量分。
- 真实 MySQL 仅 SELECT/EXPLAIN，对账后回滚；未执行评分更新、DDL 或历史数据修复。Redis 测试使用独立负数 ID，只清理测试自建键。

### 1.2 数据口径与查询顺序

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

### 1.3 质量计算

```text
批次合格金额原始值 = inbound_bill_item.qualified_qty × unit_price
批次不合格金额原始值 = inbound_bill_item.defective_qty × unit_price
产品质量分 = 产品合格金额 / (产品合格金额 + 产品不合格金额) × 10000
供应商质量分 = 全部有效产品合格金额 / 全部有效产品总金额 × 10000
```

数量存储值是实际数量 ×100，单价是分；共同缩放倍数在评分比值中抵消。乘积与累加使用 `BigInteger`，不逐批舍入，不读取采购 `total_amount` 作为质量金额。供应商直接计算原始金额比值，不平均已经舍入的产品分数。

全合格=10000，全不合格=0，无有效金额=NULL。样本出窗后，产品旧质量分需要显式 SET NULL，不能被 ORM 的默认 NOT_NULL 更新策略保留下来。

### 1.4 交付计算

- 每条采购明细 `total_amount` 只计入应交金额一次。
- 每行先累加 `确认批次数量 × 对应逾期百分比`，再加 `剩余未交数量 × 当前逾期百分比`；该加权数量乘明细金额、除以 `采购数量 ×100` 得到罚额。数量严格配平，不重复累加订单金额，也不必排序批次。
- 已确认批次按 `confirmed_at` 相对订单承诺日的天数计罚；剩余未交付金额按 D 计罚。草稿、待确认工作单不代表已交付。
- 逾期系数：按时 0%，1–3 天 25%，4–7 天 50%，8–15 天 75%，超过 15 天 100%。
- 加权罚额保留 `BigDecimal` 亚分精度，评分使用 `penaltyAmountRaw`，不是舍入后的展示罚额。1 分未交订单逾期一天：罚额 0.25 分，交付分 7500。
- 比例除法保留 32 位小数，不提前将批次金额舍入到整数分。1 分订单按时交一半、余下一半逾期 4 天时，罚额同样为 0.25 分，不能把剩余半分舍没。
- `交付分 = max(0, (应交金额 - 罚额) / 应交金额 ×10000)`，最终 HALF_UP；无有效应交金额=NULL。
- 不纳入取消责任订单；相关业务字段与触发另行审阅。

### 1.5 Redis 缓存结构与更新

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

### 1.6 重算、落库与任务

`recalcFactsForSupplier`：供应商锁 → 一致性数据库事实 → 产品质量/供应商质量/交付 → 推荐与综合 → 乐观锁更新 → 评分变化日志 → 提交后释放锁。

- 推荐分、综合分仍按 30% 价格 +30% 交付 +30% 质量 +10% 服务；缺任一输入为 NULL，不重新分配权重。
- 价格/服务不在质量交付重算时写回，避免覆盖其独立业务维护结果。
- 质量、推荐、综合分发生变化时均能持久化；指标分未变而衍生分变化也不能被日志过滤条件漏掉。
- `score_status` 与实际分数一起修正，即使分数数值相同也会修复错误的 READY/NOT_READY 状态。
- 版本冲突必须失败回滚，不允许忽略零行更新。评分与日志位于同一 DB 事务。
- D2 每日 02:00 分页处理启用且未删除的供应商，单个失败记录错误但继续处理后续供应商；不依赖新增 MQ 消息。
- `SUPPLIER_SCORE_SCHEDULED_ENABLED` 默认 false，保留开发环境安全开关；审阅通过后再开启。

### 1.7 后端文件审阅顺序

以下 Java 文件均位于 `erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/`。

| 顺序 | 文件 | 改动与重点检查 |
|---:|---|---|
| 1 | `service/scoring/SupplierScoreFactsAggregationService.java` | 先看两个样本查询窗口，再看 loadPage、validate、qualityForOrder、deliveryForOrder。核对分页不截断、全部批次、未交余额、整单异常跳过、亚分罚额与今日基线订单标记 |
| 2 | `mapper/SupplierProductMapper.java` | selectScoringOwners 只选归属字段、按主键批量查；故意不排除逻辑删除记录，只用于历史质量归属 |
| 3 | `domain/supplierscore/dto/FactsSnapshot.java` | 快照改为质量金额、产品金额临时 Map、应交金额、原始罚额及异常计数；不再使用 spAmounts 空缓存或供应商总数量 |
| 4 | `service/scoring/QualityScoreCalculator.java`、`DeliveryScoreCalculator.java`、`AggregateScoreCalculator.java` | 金额比例、零样本 NULL、全不合格 0、亚分罚额、固定权重及最终 HALF_UP |
| 5 | `service/scoring/SupplierQualityAmountCacheService.java` | Hash 字段、24h TTL、原子覆盖包含今日订单、重复增量、Lua PTTL/CAS、大整数；没有产品金额缓存 |
| 6 | `service/scoring/ScoreFactsQueryService.java` | 同一事务读取两种事实、同一锁序列化覆盖与增量、冷启动不重复加、增量只准备缓存 |
| 7 | `service/impl/SupplierScoreRecalculateServiceImpl.java` 与 `service/SupplierScoreRecalculateService.java` | 保留价格/服务、更新全部相关产品质量及推荐、显式写 NULL、状态修复、乐观锁冲突、提交后释放锁 |
| 8 | `service/impl/SupplierScoreChangeLogServiceImpl.java` | 无变化判断同时看指标/推荐/综合，避免只变衍生分时丢日志；原幂等键逻辑保留 |
| 9 | `job/SupplierScoreScheduledJob.java` | D2 按供应商分页、独立事务、单供应商失败继续、上海时区与开关 |
| 10 | `domain/supplierproduct/entity/SupplierProduct.java`、`service/impl/SupplierProductServiceImpl.java` | 旧 ai_score 不再读写；分页推荐分筛选与 VO aiScore 都映射 recommend_score |
| 11 | 公共 `erp-common/.../constant/SupplierScoreRedisKeys.java`、采购 `domain/supplierscore/constant/SupplierScoreConstants.java` | 新质量键、旧键方法移除、评分日志规则版本 SCORE_V2 |
| 12 | `service/support/ScoreRecalcPendingService.java` | 首事件投递、后续事件合并、失效窗口继承来源、发送失败处理；MQ `batchNo` 是窗口标识，不是待替换的临时业务单号 |
| 13 | `mq/SupplierScoreFireConsumer.java` | 外层供应商锁、批次匹配、真实事务成功后清 pending、异常由 MQ 重试；单一来源走增量，多来源走重建 |
| 14 | `service/support/PendingRedisSupport.java` | Lua 初始化、查询参数/返回值、Redis 或 JSON 异常不得当成无 pending |

测试文件位于 `erp-server/erp-purchase/src/test/java/com/qiheng/erp/purchase/`：

- `service/scoring/SupplierScoreFactsAggregationServiceTest.java`：多批交付、无确认入库、异常数量、提前批次、采购错误金额不影响质量、201 张跨页、窗口参数、1 分订单的亚分罚额。
- `service/scoring/ScoreFactsQueryServiceTest.java`：强制 DB、缓存命中、全量包含今日订单、冷启动不追加当前订单。
- `service/scoring/SupplierQualityAmountCacheServiceTest.java`：24h TTL、原子 RENAME、大整数、重复订单、全量后迟到增量。
- `service/scoring/SupplierFactsRecalculationTest.java`：质量/推荐落库、价格服务不被覆盖、出窗清空、状态修复、乐观锁冲突。
- `service/scoring/SupplierScoreDailyJobTest.java`：一个供应商失败不阻断下一位。
- `service/scoring/SupplierScoreFactsMysqlIT.java`：真实 13307 mapper 与独立 SQL 对账、EXPLAIN、真实实体映射；仅只读。
- `service/scoring/SupplierQualityAmountRedisIT.java`：真实 6379 TTL、大整数、今日基线/重复事件、过期未命中；清理专用测试键。
- `service/impl/SupplierScoreChangeLogServiceImplTest.java`：指标与衍生分日志过滤、幂等。
- `mq/SupplierScoreMqClosedLoopIT.java`：实际链路测试，独立 Topic/group，实际五分钟延迟、真实 Spring 事务与 Mapper、缓存和日志。
- `mq/SupplierScoreMqFixture.java`：隔离数据夹具，正数独立主键、唯一单号、原子插入、精确删除。
- `service/support/ScoreRecalcPendingServiceTest.java`：延迟等级断言为 9，防止回归。

同步文档：`docs/database/mvp-purchase-schema.md`、`mvp-warehouse-schema.md`、建库 SQL 003/004、`docs/api/erp-openapi.yaml`。

## 2. RocketMQ 闭环与生产配置

### 2.1 注解注册评分生产者

Topic 是 Broker 上区分消息用途的名称；不是 Java 包，也不需要为每个 Topic 部署一套 MQ。评分使用 `erp-supplier-score-recalc`，不注册操作日志生产者。

采购模块的 `SupplierScoreRocketMQTemplate` 继承 `RocketMQTemplate`，通过注解注册：

```java
@ExtRocketMQTemplateConfiguration(
    group = "${supplier-score.rocketmq.producer.group:erp-supplier-score-producer}"
)
public class SupplierScoreRocketMQTemplate extends RocketMQTemplate {
}
```

该注解包含 Spring 组件注册，Starter 会为专用模板创建并启动一个生产者。`ScoreRecalcPendingService` 按 `SupplierScoreRocketMQTemplate` 具体类型注入，不依赖默认模板或默认生产者组。NameServer、发送超时、同步及异步重试次数读取公共 `rocketmq` 配置，评分组名读取 `supplier-score.rocketmq.producer.group`。

不需要再配置公共 `rocketmq.producer.group`，否则会额外启用默认生产者。生产者注册、启动及关闭由 Starter 和模板生命周期负责，不额外手动调用 `start()` 或 `shutdown()`。评分消费者的 Topic 与消费组也读取 `supplier-score.rocketmq` 配置，避免发送和订阅使用不同 Topic。

NameServer 返回“没有路由”，表示当前没有 Broker 提供该 Topic 的队列信息，客户端无法定位目标。Producer group 标识发送方，consumer group 标识消费方；两者与 Topic 是不同概念，不必同名。同一生产者可发送多个 Topic，不是每加一种消息就必须加一个生产者组。

### 2.2 云端 Topic 初始化

当前云端使用 Docker 容器 `rmqnamesrv` 和 `rmqbroker`。在云服务器执行一次以下命令，显式创建评分 Topic：

```bash
docker exec rmqbroker sh mqadmin updateTopic -n rmqnamesrv:9876 -b 127.0.0.1:10911 -t erp-supplier-score-recalc -r 4 -w 4
docker exec rmqbroker sh mqadmin topicRoute -n rmqnamesrv:9876 -t erp-supplier-score-recalc
```

应用启动不自动修改 Broker 的 Topic 配置。正式 Topic 存在时不覆盖原配置。

### 2.3 延迟等级

RocketMQ 延迟等级从一开始计数。当前真实 Broker 的第八级为 `4m`、第九级为 `5m`，评分使用第九级，对应五分钟合并窗口。原代码误用 `MQ_DELAY_LEVEL_5MIN = 8` 会提前结束评分合并窗口，已修正为 9。闭环测试会读取 Broker 实际配置检查这一对应关系，不修改共享 Broker 的延迟表。

### 2.4 MQ 相关测试

- `SupplierScoreRocketMQTemplateTest` 验证真实 Starter 的注解注册、配置继承、具体类型注入和客户端关闭。
- `SupplierScoreRocketMQTemplateIT` 使用 `-Dmq.real.it=true` 启用，对注解创建的评分客户端执行只读云端集群和 Topic 路由查询，不发送消息或改动业务数据。只读路由检查不能代替业务投递消费验证。
- `SupplierScoreMqClosedLoopIT` 使用 `-Dscore.mq.e2e.write=true` 显式启用真实闭环：若正式评分 Topic 不存在则创建四读四写队列；使用唯一测试 Topic、消费组和独立正数主键，运行真实评分生产者、五分钟延迟、消费者、事务代理、Mapper、Redis 和日志服务；覆盖冷缓存、热缓存增量、多单并发合并、重复订单增量与重复消息消费；结束后只清理本次测试资源，保留正式 Topic，不回退共享单号序列。

在 `erp-server` 执行，先通过本机环境变量设置 `ERP_IT_JDBC_PASSWORD`，不要将密码写入命令或测试文件：

```powershell
mvn -pl erp-purchase -am '-Dtest=SupplierScoreMqClosedLoopIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dscore.mq.e2e.write=true' test
```

该测试会写入隔离测试数据和初始化 Topic，不能作为普通只读探测随意运行。默认连接本机 `13307/erp`、Redis `6379` 和配置中的云端 MQ；可用 `score.it.url`、`score.it.user`、`score.it.redis`、`mq.it.name-server` 指定受控环境。

测试清理额外兼容了旧 Broker 的 `removeOffset` 字段：新版客户端使用 `cleanOffset`，4.9.6 Broker 的删除组实现读取 `removeOffset`。同时发送这两个字段，仅回收本轮两个明确测试组及位点；未修改生产客户端或共享 Broker 配置。消费者关闭等待设置为三十秒，停止后持供应商锁清理 Redis。

### 2.5 MQ 真实闭环验证结果

2026-09-30 使用本机 MySQL `13307/erp`、Redis `6379` 和实际云端 RocketMQ 完成两轮五分钟延迟评分消费。普通采购模块回归 131 项、真实集成 6 项，共 137 项测试最终通过，零失败、零错误、零跳过。

三个隔离场景同时运行：冷缓存重建、热缓存新增完全入库金额、多单并发事件合并。每个供应商有两种不同价格、不同合格率的产品，断言不复用被测评分计算器。

| 核对项 | 数据库存储分值（INT×100） | 页面业务分数 |
| --- | --- | --- |
| 供应商质量分 | 6000 | 60.00 |
| 供应商交付分 | 7500 | 75.00 |
| 供应商价格分 | 8889 | 88.89 |
| 供应商综合分 | 7517 | 75.17 |
| 产品一质量/价格/推荐 | 8000 / 10000 / 8450 | 80.00 / 100.00 / 84.50 |
| 产品二质量/价格/推荐 | 5000 / 8333 / 7050 | 50.00 / 83.33 / 70.50 |

单订单场景缓存原始合格金额为 `18000000`、不合格金额为 `12000000`；两单合并分别为 `36000000`、`24000000`。原始金额使用数量存储值乘单价分，未除以数量放大倍数，避免批次舍入。每批七条指标日志，批次一致，供应商和全部供货产品状态为 READY。重复订单增量不再加金额；实际重复消息消费进度超过发送位点后，评分版本与日志条数不变。

真实数据库对账覆盖 17 个供应商，4 个有效质量订单和 9 个有效交付订单，数据库返回与独立 SQL 计算一致。异常历史订单按已确认策略跳过并告警，没有修复或覆盖原始数据。

两轮测试供应商、产品、采购与入库记录、评分日志、Redis pending/质量键及测试 Topic 已清理；正式评分 Topic 保留，共享单号序列未回退。

## 3. 评分日志来源改造

评分日志从两个单值来源字段改为 `related_sources` JSON 数组。多采购单合并保存全部来源；ID 使用字符串；人工日志记录真实用户，系统日志记录具体场景；报价清空原因完整保存。评分公式、缓存模型、外层锁和重算主方法结构保持不变。

| 触发场景 | 来源类型 | ID 与编号如何填 |
| --- | --- | --- |
| 完全入库、多单合并 | PURCHASE_ORDER | 每张采购单 ID 与采购单号，不能搭配入库单号 |
| 报价修改、清空、到期 | SUPPLIER_PRODUCT | 供货关系 ID；没有独立编号，businessNo 为 null |
| 参考采购价修改 | PRODUCT | 产品 ID 与产品编码 |
| 服务分修改 | SUPPLIER | 供应商 ID 与供应商编码 |
| 每日校正 | 空数组 | 日期写原因，不虚构来源 |

来源按类型与 ID 去重、稳定排序；同一对象编号冲突拒绝写入，不取首单或截断来源列表。USER 保存真实 ID 与姓名；SYSTEM 的 ID 为 null，名称为场景说明。

相关代码审阅顺序：

1. `docs/database/sql/migrate_supplier_score_log_sources.sql`、`migrate_supplier_score_log_reason_capacity.sql`：看字段删除、新 JSON 数组和身份约束。原因请求上限仍为 500 字，日志扩到 600 字，额外空间保存校正说明，不截断原文。
2. `ScoreSourceBusinessType.java`、`ScoreChangeSource.java`：四种来源类型、字符串 ID、可空编号；ALWAYS 保证空编号明确返回 null。
3. `RecalcContext.java`、`ScoreRecalcContext.java`、`ScoreChangeBatchCommand.java`：完整日志来源和事实计算订单集合分开，操作人和原因不会在公共映射中丢失。
4. `SupplierScoreRecalculateServiceImpl.java`：各入口如何填上表来源、实际变化才生成批次与记录日志、人工身份如何读取。报价到期只收集真正到期且有旧价格分的关系。
5. `SupplierProductServiceImpl.java`：报价修改和清空都将本次请求原因传给重算，不从清空后的实体反取原因。
6. `PurchaseOrderServiceImpl.java`：完全入库事件使用采购单 ID 加采购单号。
7. `SupplierScoreFireConsumer.java`：pending 的所有采购单转换成完整来源，没有只取第一单；外层锁、事务成功后清理 pending 不变。
8. `SupplierScoreScheduledJob.java`：每日来源为空数组、日期写原因、系统名称明确。
9. `SupplierScoreChangeLogServiceImpl.java`：统一校验、完整去重排序、冲突拒绝、JSON 单次编码、幂等，以及逐条说明“仅综合分／推荐分校正”。原人工原因 500 字限制与最终日志 600 字限制分开。
10. `SupplierScoreChangeLog.java`、`SupplierScoreChangeLogVo.java`：数据库 JSON 字符串与响应数组的转换；VO 局部覆盖全局 NON_NULL，明确返回缺失评分与系统用户 ID 的 null。
11. `ProductReferencePriceDto.java`、`ProductServiceImpl.java`：移除了没有产品表字段和真实校验支撑的 version 参数；原有产品锁、供应商锁与事务不变。
12. `erp-openapi.yaml`、`mvp-purchase-schema.md`、`PRD.md`：看接口、数据库和展示规则是否与上述一致。
13. 前端 `purchase/types.ts`、`purchase/api.ts`、`SupplierScoreChangeLogDialog.vue`：看来源严格校验和全部展开，真实用户／系统名称，衍生校正不误显示基础指标变化。产品前端的参考价表单与请求也同步删除无效 version。

## 4. 真实评分测试保留数据与锁修复

2026-10-07 已在开发库 `localhost:13307/erp` 保留 **35 条实际后端写入的评分日志**。不是手工构造日志 INSERT。现有业务记录未覆盖；本次测试使用独立供应商、产品、采购单。默认测试仍清理，只有显式启用保留并全部验证成功才留下。

### 4.1 保留的供应商

| 场景 | 供应商ID | 日志数 | 批次号 |
| --- | --- | --- | --- |
| 真实HTTP：服务分、报价、参考价、清空、到期、每日校正 | 2107530545189076994（编码S0006） | 16 | SC2026100700001 至 SC2026100700008 |
| MQ冷启动 | 1791309518786001 | 7 | SC2026100700009 |
| MQ暖缓存 | 1791309518786012 | 6 | SC2026100700010 |
| MQ两单合并 | 1791309518786023 | 6 | SC2026100700011 |

HTTP供应商名称：`评分日志HTTP测试-e1d49275-d8f5-446b-b9d7-0c3167c73274`。产品编码 `P000053`，产品ID `2107530545663033346`，供货关系ID `2107530546002771970`。

三个MQ供应商名称分别为 `评分消息测试-cold-ecc8f5227d1f4138`、`评分消息测试-warm-ecc8f5227d1f4138`、`评分消息测试-merged-ecc8f5227d1f4138`。

MQ供应商最终质量分60.00、交付分75.00、价格分88.89、综合分75.17。HTTP样本最后特意执行报价过期和无入库事实的每日校正，所以质量、交付、价格、综合分为null，服务分86.00保留，这是测试预期，不是漏算。

### 4.2 只读查询 SQL

```sql
SELECT id, supplier_id, supplier_product_id, batch_no, metric_type,
       metric_score_before, metric_score_after,
       product_recommend_score_before, product_recommend_score_after,
       supplier_overall_score_before, supplier_overall_score_after,
       trigger_type, related_sources,
       operator_type, operator_id, operator_name, reason, create_time
FROM supplier_score_change_log
WHERE supplier_id IN (
    2107530545189076994,
    1791309518786001,
    1791309518786012,
    1791309518786023
)
ORDER BY supplier_id, id;
```

数据库评分字段按 INT×100 保存，如 6000 表示 60.00。`related_sources` 可以展开 JSON 查看。合并批次 SC2026100700011 的每条日志都应包含下面两张采购单，而不是只取首单：

| 采购单ID | 采购单号 |
| --- | --- |
| 1791309518786028 | POecc8f5227d1f4138hmys8d8s8c |
| 1791309518786034 | POecc8f5227d1f4138hmys8d8s8i |

HTTP初始质量、交付及权重是独立夹具预置，后续调整经真实 HTTP 业务入口写入。MQ使用隔离订单夹具、实际生产者、五分钟延迟及实际消费者，不等于完整采购确认流程全部经 HTTP 创建。临时 MQ 资源已回收，只保留业务样本、日志及正常 TTL 的质量缓存；认证会话已注销。

这些是开发验收样本，不是真实经营单据。后续每日校正可能新增日志或改变当前分数，不应据此删除本次已保留的历史记录。

### 4.3 本次只修了哪处锁

1. `ProductServiceImpl.java`：仅参考价入口配置 `leaseTime=-1`，避免批量重算超过 30 秒时锁提前过期。
2. `DistributedLockAspect.java`：负租期调用不带固定 lease 的重载，启用看门狗续约；其他入口固定租期逻辑保持。切面仍在事务外层，成功或异常后释放本线程持有的锁。
3. `DistributedLock.java`：补中文说明，默认值仍 30 秒。

真实 HTTP 请求等待供应商行锁 35000 毫秒后，产品锁剩余 TTL 25160 毫秒，竞争线程无法取得；成功和异常返回后均能重新取得。相关 272 项回归通过，包括新增 5 项切面单元测试及 23 项真实数据库/Redis 测试。当前检查未发现其他新增阻塞问题，不保证生产环境永远没有死锁或投递遗漏。

验证证据：[真实HTTP与续约](../_logs/score-log-watchdog-http-retained-review-20261007.md)、[真实MQ执行](../_logs/score-log-watchdog-mq-retained-20261007.log)、[272项回归](../_logs/score-log-watchdog-related-final-20261007.log)、[最终只读数据库核对](../_logs/score-retained-db-final-readonly-20261007.log)。

## 5. 验证结果汇总与已知遗留

### 5.1 真实数据规模与证据

13307 的实际规模：20 张采购单、23 条采购明细、25 张入库单、29 张入库明细、17 位未删除供应商。当前有效质量订单 4 张，交付订单 9 张；Java 与独立 SQL 的产品质量金额、供应商质量总额、应交金额和未舍入罚额逐项一致。

样例供应商 `2088323218453118977`：

- 合格金额原始值 2714800，不合格金额 0；2 张有效质量订单，质量分 100.00。
- 到期应交 35786 分，罚额原始值 4936 分；3 张有效交付订单，交付分 86.21。

历史异常中有完全入库订单缺少完成时间、累计数量与确认批次不一致；测试确认按约定跳过并输出告警，没有回填或假设为未交货。

EXPLAIN 已覆盖生产使用的字段投影与关联查询：订单查询使用现有供应商/窗口索引，采购明细命中 `idx_purchase_order_item_order`，入库主表命中 `idx_inbound_bill_source`，入库明细命中 `idx_inbound_bill_item_bill`，归属查找使用 PRIMARY。本次不新增索引。各次验证热态每供应商的读取及独立 SQL 对账约 15–74ms；这只证明当前小数据执行正常，不是百万级压测结论。

真实 Redis 测试测得 TTL 86399 秒，大整数精确、重复事件不追加、全量已包含的今日订单不追加、失效键不会从零增量创建；测试键已清理。

### 5.2 测试统计

| 轮次 | 结果 |
|---|---|
| 质量/交付实施轮 | 采购模块 125 项通过（123 单测 + 2 真实 MySQL/Redis 集成），失败/错误/跳过均为 0 |
| 清理无用代码后重跑 | 115 项通过（114 单测 + 1 真实 MySQL 集成），失败/错误/跳过均为 0；移除旧缓存专属 8 项与重复系数方法专属 1 项，未重跑真实 Redis 集成 |
| MQ 闭环轮 | 131 项普通回归 + 6 项真实集成 = 137 项通过，零失败零错误零跳过 |
| 日志来源改造轮 | 267 项通过（244 单测 + 23 真实 MySQL/Redis，含 6 项并发与回滚）；前端 33 项适配边界断言、997 个 OpenAPI 引用检查、生产构建、真实 Edge 展示测试通过 |
| 锁修复轮 | 272 项回归通过，含新增 5 项切面单元测试及 23 项真实数据库/Redis 测试 |

前端完整构建曾遇到已有无关错误 `ProductManageView.vue:401`：`ProductListItem` 缺少 `version`。

### 5.3 已知遗留（不属于评分改造范围）

- `AiAssistantControllerHttpStatusTest.historyReturns404ForMissingOrForeignConversation` 预期 404，实际 200。该问题在评分改造前已存在，未修改或隐藏；不能宣称全仓测试全部通过。详见 [当前未完成事项](project-open-items.md)。
- 架构仍为事务提交后投递并由每日校正兜底，没有新增 outbox；正常闭环通过不等于跨数据库与 MQ 的绝对不丢消息保证，也不能证明所有生产负载下绝无死锁。
- 有限测试不能保证生产环境永远没有死锁或投递遗漏。原架构不加 outbox、保留每日校正补偿的约定不变。
