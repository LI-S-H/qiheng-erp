# 2026-10-08 未提交改动修复与验证

## 本次修复范围

- 采购编辑先打开详情表单，当前推荐分按去重供货关系异步补查；无权限、404 或超时仅降级为空，不阻止编辑。关闭、重开、换供应商或换行后旧结果不能覆盖新表单。
- 库存页复用局部加载遮罩，覆盖查询、刷新、重置和分页；保留旧数据并锁定操作。移动端风险快捷筛选改为整行宽度。
- 采购审核及评分消息测试同步当前实现，保持严格 Mockito 校验，真实框架 Converter 往返验证 19 位 Long。
- 异常 MQ 的独立测试配置标记为 TestConfiguration；应用使用 SpringBootApplication 的扫描参数保留 Boot 测试排除规则，生产扫描包不变。
- 采购取消契约恢复当前仅版本号请求，数据库责任取消字段明确为预留。旧评分 SQL 仅保留弃用注释，不能执行第二套评分算法。
- 最终契约复核修正供应商 scoreBasisAmount 正则重复转义，并对供应商、供货关系、候选供货关系和工作台供应商四种金额契约执行正常金额及非法金额匹配回归。
- 初始化脚本移除历史全表乘100迁移；仅为明确采购种子补齐来源、单价及预计到货快照，不改共享开发库历史事实。

## 已完成验证

### 后端与真实开发库

定向单元测试 24 项通过：审核 9、入库快照 3、pending 5、Template 4、库存 3。

PurchaseSnapshotStockHttpIT 启动真实后端，通过 HTTP 创建独立夹具，使用真实 MySQL 和 Redis：

- 审核前分数为空；审核后数据库保存 8123、0、NULL，HTTP 分别返回 81.23、0、null。
- 后续供货评分变化不修改历史采购快照。
- 持有供应商行锁后审核等待，锁释放后读取新分数 9234，而不是旧分数。
- 重复审核不新增第二张入库单。
- 安全库存存储125返回1.25；等于安全阈值仍为风险，零安全库存不错误判低库存。
- 风险条件与仓库、产品筛选按 AND 组合；低库存汇总2、无可用库存汇总1。
- 无论测试成功或失败，精确清理本次唯一备注和 ID 的夹具，不清共享 Redis、不动原业务记录。

另通过应用扫描隔离回归、工作台指标与待办、系统异常消费者及业务编码失败测试。此次没有再次执行真实 MQ 投递消费闭环；消息 Converter 测试不等同于真实网络投递。

### 初始化 SQL

在本机13307新建专用临时库，产品002脚本执行一次，仓库003和采购004脚本连续执行两轮：

- 两轮采购金额均为84480，数量2400，单价3520，没有重复乘100。
- 全部采购明细金额与数量×单价口径相符。
- 采购入库种子的来源、有效单价、预计到货快照缺失数为0。
- 最终删除的只有本次创建的临时库，未在共享erp库执行初始化。

静态种子契约检查覆盖12张采购单、15条明细、4张采购入库种子。旧种子已有的历史数量不配平未伪造修补，评分仍应整单跳过并告警。产品002是一次性种子，不宣称全部初始化脚本均可重复运行。

### 前端

- 采购预览6个真实浏览器场景通过：采购-only权限、404、超时、重复关系去重、null/零分、关闭后旧补查隔离。故障通过模块Mock注入，不冒充真实供货详情HTTP测试。
- 铃铛条目详情、无效todoId、底部入口、DENIED空态回归通过；DENIED夹具确实生效，等待Popover关闭避免把退出动画误判为业务详情。
- 库存查询、重置、刷新、分页、组合筛选、局部加载和1440/1115/390视口回归通过。
- 工作台EMPTY/DENIED状态回归通过，夹具在Mock模块返回点确实生效，保留权限入口和空态布局断言。
- 会话隔离、库存API及种子静态测试通过；OpenAPI检查及生产构建通过，vue-tsc无类型错误。Vite首次被沙箱realpath权限拦截，提权重跑成功，保留已有VueUse注解警告。

截图目录：[独立最终复跑](../qa-screenshots/2026-10-08-remaining-final/)。最终库存截图等待页面进入遮罩退出后再取证；提交只保留通过场景的截图，不包含修复过程中的失败截图。

## 剩余边界

- 全量后端回归被未修改的 AiAssistantControllerHttpStatusTest 阻断：历史查询预期HTTP404，实际200。本轮保留原断言，不扩展修复AI模块，不能声称全仓库全量通过。
- 提交前再次复跑：采购、库存及评分消息测试通过；另一会话正在修改的 AI 模块因 RunnableConfig.Builder.context 方法不存在及 void 链式调用而编译失败，后续工作台、应用隔离及真实 HTTP 测试未能再次执行。上述 HTTP 结果来自本轮先前已通过的运行，不将该次复跑记为全量成功。
- 验证了单个供应商行锁等待及重复审核，并非对所有生产并发组合“绝无死锁”的证明。
- 责任取消、部门数据权限为后续扩展，没有为了文档声明而扩大本轮业务实现。

## 建议审阅顺序

1. `erp-web/src/modules/purchase/orders/views/PurchaseOrderManageView.vue`：先看openEditDialog非阻塞补查、权限和旧请求防护，再看仅预览分数不提交。
2. `erp-web/src/modules/purchase/api.ts`、`erp-web/scripts/smoke-purchase-score-preview.cjs`：请求配置和Mock归属，六个回归场景是否真正覆盖失败分支。
3. `erp-web/src/modules/warehouse/stocks/views/WarehouseStockManageView.vue`、`erp-web/scripts/smoke-warehouse-stocks.cjs`：局部加载与移动端，断言未被弱化。
4. `PurchaseOrderApprovalScoreTest.java`、`ScoreRecalcPendingServiceTest.java`、`SupplierScoreRocketMQTemplateTest.java`：严格桩、对象消息、网络字节及Long往返。
5. `ErpApplication.java`、`SystemExceptionRecordClosedLoopIT.java`、`ApplicationTestConfigurationIsolationTest.java`：扫描范围与测试隔离。
6. `PurchaseSnapshotStockHttpIT.java`：真实HTTP、数据库断言及精确清理。
7. `docs/database/sql/004_mvp_purchase.sql`、`013_backfill_development_supplier_scores.sql`、`erp-web/scripts/test-purchase-seed-contract.cjs`：初始化只处理明确种子、不重复放大、不再维护旧评分算法。
8. `docs/api/erp-openapi.yaml`、数据库说明、功能说明与待办清单：契约和实际代码一致，已验证与未来扩展分开。
