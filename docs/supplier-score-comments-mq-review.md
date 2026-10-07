# 评分注释与真实 MQ 闭环审阅清单

> 本文以下内容记录 2026-09-30 的历史改动和验证，不代表最新实现。2026-10-06 已将原 `batchToken` 统一为 `batchNo`，通过 `BillNoGenerator` 生成 SC 业务批次号，并使用专用 Template 同步延迟发送；当前审阅顺序和本轮测试结果见 [统一评分批次号审查记录](E:/Projects/ERP_new/_logs/task-review-20261006-184750.md)。本轮真实 MQ 是否通过以该记录为准，不复用下文历史成功结果。

## 本次完成内容

本次没有重写已经审阅的缓存结构、数据库查询或协调层。生产业务行为仅修正一项：`MQ_DELAY_LEVEL_5MIN` 从 `8` 改为 `9`。实际云端 Broker 第八级为四分钟、第九级为五分钟，旧值会提前结束评分合并窗口。

中文注释补齐公共入口的参数、返回值及事务/外层锁边界；私有方法仅解释作用，删除旧的“统一核心重算”“临时批次号待替换”等描述。所有变更文件严格 UTF-8 解码通过，无替换字符。

正式 Topic `erp-supplier-score-recalc` 已初始化，Broker `8.154.27.144:10911` 提供四个读队列和四个写队列。存在的 Topic 配置不覆盖；业务应用启动仍不自动创建 Topic。

## 建议审阅顺序

| 顺序 | 具体文件 | 本次修改及检查重点 |
| --- | --- | --- |
| 1 | [SupplierScoreConstants.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/domain/supplierscore/constant/SupplierScoreConstants.java) | 唯一生产行为修改：五分钟延迟等级改为 9；历史与当前规则版本注释区分。 |
| 2 | [ScoreRecalcPendingService.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/support/ScoreRecalcPendingService.java) | 仅注释：首事件投递、后续事件合并、失效窗口继承来源、发送失败处理；MQ batchToken 是窗口标识，不是待替换的临时业务单号。 |
| 3 | [SupplierScoreFireConsumer.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/mq/SupplierScoreFireConsumer.java) | 仅注释：外层供应商锁、批次匹配、真实事务成功后清 pending、异常由 MQ 重试；单一来源走增量，多来源走重建。 |
| 4 | [PendingRedisSupport.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/support/PendingRedisSupport.java) | 仅注释：Lua 初始化、查询参数/返回值、Redis 或 JSON 异常不得当成无 pending。 |
| 5 | [SupplierScoreRecalculateService.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/SupplierScoreRecalculateService.java) | 仅注释：明确服务分、报价、参考价、报价到期与事实重算入口各自负责的功能和事务边界。 |
| 6 | [SupplierScoreRecalculateServiceImpl.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/impl/SupplierScoreRecalculateServiceImpl.java) | 仅注释：服务/价格独立路径不读历史事实；补服务分、参考价、报价到期重写方法参数；appendChanges 只承担通用日志；持久化说明包含价格分及可空分数。 |
| 7 | [SupplierScoreFactsAggregationService.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/scoring/SupplierScoreFactsAggregationService.java) | 仅注释：SQL 实际投影字段与描述一致，交付归并是应交金额与折算罚额，不是质量金额。SQL 未改。 |
| 8 | [ScoreRecalcContext.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/domain/supplierscore/dto/ScoreRecalcContext.java) | 仅注释：采购单来源、MQ 批次与定时批次、默认日志说明。 |
| 9 | [ScoreRecalcResult.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/domain/supplierscore/dto/ScoreRecalcResult.java) | 仅注释：metricChanges 同时承载供应商和产品变化；状态修正不一定产生指标日志；服务分也会影响推荐分。 |
| 10 | [RecalcScope.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/domain/supplierscore/dto/RecalcScope.java) | 仅注释：只用于完整事实重算，不再列出已经拆开的同步入口。 |
| 11 | [SupplierScoreEventTrigger.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/domain/supplierscore/mq/SupplierScoreEventTrigger.java) | 仅注释：当前 MQ 来源为首次完全入库，定时任务直接重算；pending 保留所有来源，不只是首单。 |
| 12 | [SupplierScoreMqClosedLoopIT.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/test/java/com/qiheng/erp/purchase/mq/SupplierScoreMqClosedLoopIT.java) | 新增实际链路测试：独立 Topic/group，实际五分钟延迟、真实 Spring 事务与 Mapper、缓存和日志；重复消息以 Broker 消费位点证明 ACK，再查评分版本和日志。 |
| 13 | [SupplierScoreMqFixture.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/test/java/com/qiheng/erp/purchase/mq/SupplierScoreMqFixture.java) | 新增隔离数据夹具：正数独立主键、唯一单号、原子插入、精确删除；不会修改现有供应商或库存。 |
| 14 | [ScoreRecalcPendingServiceTest.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/test/java/com/qiheng/erp/purchase/service/support/ScoreRecalcPendingServiceTest.java) | 同步更新延迟等级断言为 9，防止回归。 |
| 15 | [SupplierScoreRocketMQTemplateIT.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/test/java/com/qiheng/erp/purchase/mq/SupplierScoreRocketMQTemplateIT.java) | 增加可显式启用的测试资源回收核查；默认只读，只有 repair 开关才删除匹配独立测试格式的组。 |

`SupplierQualityAmountCacheService.java`、`ScoreFactsQueryService.java`、`FactsSnapshot.java` 本次未修改。
配置与执行方式见 [rocketmq-producer-configuration.md](E:/Projects/ERP_new/docs/rocketmq-producer-configuration.md)；原交付计划的延迟等级描述也已同步更新。

## 真实验证结果

2026-09-30 使用本机 MySQL `13307/erp`、Redis `6379` 和实际云端 RocketMQ 完成两轮五分钟延迟评分消费。普通采购模块回归 131 项、真实集成 6 项，共 137 项测试最终通过，零失败、零错误、零跳过。真实集成覆盖 MQ 闭环、真实 Mapper SQL 对账/锁顺序、Redis、路由与清理核查。

三个隔离场景同时运行：冷缓存重建、热缓存新增完全入库金额、多单并发事件合并。每个供应商有两种不同价格、不同合格率的产品，断言不复用被测评分计算器。

| 核对项 | 数据库存储分值（INT×100） | 页面业务分数 |
| --- | --- | --- |
| 供应商质量分 | 6000 | 60.00 |
| 供应商交付分 | 7500 | 75.00 |
| 供应商价格分 | 8889 | 88.89 |
| 供应商综合分 | 7517 | 75.17 |
| 产品一质量/价格/推荐 | 8000 / 10000 / 8450 | 80.00 / 100.00 / 84.50 |
| 产品二质量/价格/推荐 | 5000 / 8333 / 7050 | 50.00 / 83.33 / 70.50 |

单订单场景缓存原始合格金额为 `18000000`、不合格金额为 `12000000`；两单合并分别为 `36000000`、`24000000`。原始金额使用数量存储值乘单价分，未除以数量放大倍数，避免批次舍入。
每批七条指标日志，批次一致，供应商和全部供货产品状态为 READY。重复订单增量不再加金额；实际重复消息消费进度超过发送位点后，评分版本与日志条数不变。

真实数据库对账覆盖 17 个供应商，4 个有效质量订单和 9 个有效交付订单，数据库返回与独立 SQL 计算一致。异常历史订单按已确认策略跳过并告警，没有修复或覆盖原始数据。
当前样本表规模为采购单 20、采购明细 23、入库单 25、入库明细 29、供货关系 14；查询约 9–30ms。EXPLAIN 使用采购订单、采购明细关联、入库来源、入库明细关联和主键索引；未新增索引。该数据规模不足以推导生产大数据量压测结论。

## 测试资源与边界

两轮测试供应商、产品、采购与入库记录、评分日志、Redis pending/质量键及测试 Topic 已清理；正式评分 Topic 保留，共享单号序列未回退。
最后独立核查确认第二轮数据库测试记录为零、Redis 键为零、测试路由不存在、消费组不存在、正式评分路由仍在。

测试清理额外兼容了旧 Broker 的 `removeOffset` 字段：新版客户端使用 `cleanOffset`，当前 [4.9.6 Broker 删除组实现](https://github.com/apache/rocketmq/blob/rocketmq-all-4.9.6/broker/src/main/java/org/apache/rocketmq/broker/processor/AdminBrokerProcessor.java#L676)读取 `removeOffset`。同时发送这两个字段，仅回收本轮两个明确测试组及位点；未修改生产客户端或共享 Broker 配置。
消费者关闭等待设置为三十秒，停止后持供应商锁清理 Redis；各项资源独立尝试回收，确保客户端最终关闭。

本次验证评分事件发布、延迟、消费、真实事务评分落库、缓存与日志闭环，不替代仓库入库确认接口和库存流水完整验收。没有增加 outbox；不能承诺所有生产故障下绝不遗漏，也不能用本轮并发测试证明所有生产负载下绝无死锁。
