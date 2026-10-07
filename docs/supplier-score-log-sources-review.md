# 评分日志来源改造：最终审阅清单

2026-10-07补充：参考价固定30秒租期风险已修复；按最新授权重新真实测试并保留35条日志。此前“测试数据无残留”属于上一轮清理结果，本轮不再清理成功样本。最新数据与SQL见[真实测试保留清单](E:/Projects/ERP_new/docs/supplier-score-retained-data-review.md)。

## 本次完成的功能

评分日志从两个单值来源字段改为 `related_sources` JSON 数组。多采购单合并保存全部来源；ID 使用字符串；人工日志记录真实用户，系统日志记录具体场景；报价清空原因完整保存。评分公式、缓存模型、外层锁和重算主方法结构保持不变。

| 触发场景 | 来源类型 | ID 与编号如何填 |
| --- | --- | --- |
| 完全入库、多单合并 | PURCHASE_ORDER | 每张采购单 ID 与采购单号，不能搭配入库单号 |
| 报价修改、清空、到期 | SUPPLIER_PRODUCT | 供货关系 ID；没有独立编号，businessNo 为 null |
| 参考采购价修改 | PRODUCT | 产品 ID 与产品编码 |
| 服务分修改 | SUPPLIER | 供应商 ID 与供应商编码 |
| 每日校正 | 空数组 | 日期写原因，不虚构来源 |

来源按类型与 ID 去重、稳定排序；同一对象编号冲突拒绝写入，不取首单或截断来源列表。USER 保存真实 ID 与姓名；SYSTEM 的 ID 为 null，名称为场景说明。

## 按此顺序审阅

1. [migrate_supplier_score_log_sources.sql](E:/Projects/ERP_new/docs/database/sql/migrate_supplier_score_log_sources.sql)、[migrate_supplier_score_log_reason_capacity.sql](E:/Projects/ERP_new/docs/database/sql/migrate_supplier_score_log_reason_capacity.sql)：看字段删除、新 JSON 数组和身份约束。原因请求上限仍为 500 字，日志扩到 600 字，额外空间保存校正说明，不截断原文。开发库已执行，不必再次迁移。
2. [ScoreSourceBusinessType.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/domain/supplierscore/enums/ScoreSourceBusinessType.java)、[ScoreChangeSource.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/domain/supplierscore/dto/ScoreChangeSource.java)：看四种来源类型、字符串 ID、可空编号；ALWAYS 保证空编号明确返回 null。
3. [RecalcContext.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/domain/supplierscore/dto/RecalcContext.java)、[ScoreRecalcContext.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/domain/supplierscore/dto/ScoreRecalcContext.java)、[ScoreChangeBatchCommand.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/domain/supplierscore/dto/ScoreChangeBatchCommand.java)：看完整日志来源和事实计算订单集合分开，操作人和原因不会在公共映射中丢失。
4. [SupplierScoreRecalculateServiceImpl.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/impl/SupplierScoreRecalculateServiceImpl.java)：看各入口如何填上表来源、实际变化才生成批次与记录日志、人工身份如何读取。报价到期只收集真正到期且有旧价格分的关系。
5. [SupplierProductServiceImpl.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/impl/SupplierProductServiceImpl.java)：看报价修改和清空都将本次请求原因传给重算，不从清空后的实体反取原因。
6. [PurchaseOrderServiceImpl.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/impl/PurchaseOrderServiceImpl.java)：看完全入库事件使用采购单 ID 加采购单号；原来的 ID/编号对象不配对问题已修正。
7. [SupplierScoreFireConsumer.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/mq/SupplierScoreFireConsumer.java)：看 pending 的所有采购单转换成完整来源，没有只取第一单；原外层锁、事务成功后清理 pending 不变。
8. [SupplierScoreScheduledJob.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/job/SupplierScoreScheduledJob.java)：看每日来源为空数组、日期写原因、系统名称明确。
9. [SupplierScoreChangeLogServiceImpl.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/impl/SupplierScoreChangeLogServiceImpl.java)：重点看统一校验、完整去重排序、冲突拒绝、JSON 单次编码、幂等，以及逐条说明“仅综合分／推荐分校正”。原人工原因 500 字限制与最终日志 600 字限制分开。
10. [SupplierScoreChangeLog.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/domain/supplierscore/entity/SupplierScoreChangeLog.java)、[SupplierScoreChangeLogVo.java](E:/Projects/ERP_new/erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/domain/supplierscore/vo/SupplierScoreChangeLogVo.java)：看数据库 JSON 字符串与响应数组的转换；VO 局部覆盖全局 NON_NULL，明确返回缺失评分与系统用户 ID 的 null。
11. [ProductReferencePriceDto.java](E:/Projects/ERP_new/erp-server/erp-product/src/main/java/com/qiheng/erp/product/domain/dto/ProductReferencePriceDto.java)、[ProductServiceImpl.java](E:/Projects/ERP_new/erp-server/erp-product/src/main/java/com/qiheng/erp/product/service/impl/ProductServiceImpl.java)：移除了没有产品表字段和真实校验支撑的 version 参数；原有产品锁、供应商锁与事务不变。
12. [erp-openapi.yaml](E:/Projects/ERP_new/docs/api/erp-openapi.yaml)、[mvp-purchase-schema.md](E:/Projects/ERP_new/docs/database/mvp-purchase-schema.md)、[PRD.md](E:/Projects/ERP_new/docs/product/PRD.md)：看接口、数据库和展示规则是否与上述一致。
13. [purchase/types.ts](E:/Projects/ERP_new/erp-web/src/modules/purchase/types.ts)、[purchase/api.ts](E:/Projects/ERP_new/erp-web/src/modules/purchase/api.ts)、[SupplierScoreChangeLogDialog.vue](E:/Projects/ERP_new/erp-web/src/modules/purchase/components/SupplierScoreChangeLogDialog.vue)：看来源严格校验和全部展开，真实用户／系统名称，衍生校正不误显示基础指标变化。产品前端的参考价表单与请求也同步删除无效 version。

## 已验证的结果

- 相关 clean 测试 **267 项通过**：244 项单元测试、23 项真实 MySQL/Redis 测试，包括 6 项并发与回滚测试。
- 完整 Spring Boot/Tomcat 启动后，真实登录和业务 HTTP 请求通过；实际落库 16 条日志逐条核对。报价过期、每日校正使用真实服务入口、同一供应商外层锁，未运行影响其他供应商的全局任务。
- 500 字中文原因完整保存；派生日志加说明后 511 字正常提交。空报价、空评分、系统用户 ID 与无编号来源明确返回 null。
- 云端 MQ 实际五分钟延迟消费，冷／暖缓存及两单合并通过；来源数组、独立预期分数和重复消费幂等均验证。该测试使用隔离数据库夹具及真实生产者／消费者，不代表采购入库全过程 HTTP 已全部覆盖；采购触发 ID／编号配对另有入口单元测试。
- 前端 33 项适配边界断言、997 个 OpenAPI 引用检查、生产构建、真实 Edge 展示测试通过。浏览器展示使用 Mock，不替代以上真实后端验收。
- 2026-10-07 再次连接 13307 只读核对，新结构正确，HTTP/MQ 测试数据均无残留。仅清理测试自身数据，正式 Topic、已有业务数据和 SC 编号计数器保留。

真实验证明细：[HTTP 逐场景结果](E:/Projects/ERP_new/_logs/score-log-sources-http-review-20261006.md)、[最终 HTTP 日志](E:/Projects/ERP_new/_logs/score-log-sources-http-final-20261006.log)、[真实 MQ 日志](E:/Projects/ERP_new/_logs/score-log-sources-mq-real-20261006.log)、[相关回归日志](E:/Projects/ERP_new/_logs/score-log-sources-related-final-20261006.log)、[最终数据库核对](E:/Projects/ERP_new/_logs/score-log-db-final-readonly-20261007.log)。

## 剩余说明

扩大全后端检查时，现有 `AiAssistantControllerHttpStatusTest.historyReturns404ForMissingOrForeignConversation` 失败：预期 HTTP 404，实际 200。未在本次评分改造中修改或隐藏，不能宣称全仓测试全部通过。

当前覆盖场景未发现分数错误、死锁或重复消费造成的数据错误；有限测试不能保证生产环境永远没有死锁或投递遗漏。原架构不加 outbox、保留每日校正补偿的约定不变。
