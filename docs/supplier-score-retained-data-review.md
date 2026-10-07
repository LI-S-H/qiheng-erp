# 真实评分测试保留数据与锁修复

2026-10-07 已在开发库 `localhost:13307/erp` 保留 **35 条实际后端写入的评分日志**。不是手工构造日志 INSERT。现有业务记录未覆盖；本次测试使用独立供应商、产品、采购单。默认测试仍清理，只有显式启用保留并全部验证成功才留下。

## 直接看这些供应商

| 场景 | 供应商ID | 日志数 | 批次号 |
| --- | --- | --- | --- |
| 真实HTTP：服务分、报价、参考价、清空、到期、每日校正 | 2107530545189076994（编码S0006） | 16 | SC2026100700001 至 SC2026100700008 |
| MQ冷启动 | 1791309518786001 | 7 | SC2026100700009 |
| MQ暖缓存 | 1791309518786012 | 6 | SC2026100700010 |
| MQ两单合并 | 1791309518786023 | 6 | SC2026100700011 |

HTTP供应商名称：`评分日志HTTP测试-e1d49275-d8f5-446b-b9d7-0c3167c73274`。产品编码 `P000053`，产品ID `2107530545663033346`，供货关系ID `2107530546002771970`。

三个MQ供应商名称分别为 `评分消息测试-cold-ecc8f5227d1f4138`、`评分消息测试-warm-ecc8f5227d1f4138`、`评分消息测试-merged-ecc8f5227d1f4138`。

MQ供应商最终质量分60.00、交付分75.00、价格分88.89、综合分75.17。HTTP样本最后特意执行报价过期和无入库事实的每日校正，所以质量、交付、价格、综合分为null，服务分86.00保留，这是测试预期，不是漏算。历史前后变化请看日志。

## 只读查询SQL

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

数据库评分字段按INT×100保存，如6000表示60.00。`related_sources` 可以展开JSON查看。合并批次SC2026100700011的每条日志都应包含下面两张采购单，而不是只取首单：

| 采购单ID | 采购单号 |
| --- | --- |
| 1791309518786028 | POecc8f5227d1f4138hmys8d8s8c |
| 1791309518786034 | POecc8f5227d1f4138hmys8d8s8i |

HTTP初始质量、交付及权重是独立夹具预置，后续调整经真实HTTP业务入口写入。MQ使用隔离订单夹具、实际生产者、五分钟延迟及实际消费者，不等于完整采购确认流程全部经HTTP创建。临时MQ资源已回收，只保留业务样本、日志及正常TTL的质量缓存；认证会话已注销。

这些是开发验收样本，不是真实经营单据。后续每日校正可能新增日志或改变当前分数，不应据此删除本次已保留的历史记录。

## 本次只修了哪处锁

1. [ProductServiceImpl.java](E:/Projects/ERP_new/erp-server/erp-product/src/main/java/com/qiheng/erp/product/service/impl/ProductServiceImpl.java)：仅参考价入口配置 `leaseTime=-1`，避免批量重算超过30秒时锁提前过期。
2. [DistributedLockAspect.java](E:/Projects/ERP_new/erp-server/erp-common/src/main/java/com/qiheng/erp/common/aop/DistributedLockAspect.java)：负租期调用不带固定lease的重载，启用看门狗续约；其他入口固定租期逻辑保持。切面仍在事务外层，成功或异常后释放本线程持有的锁。
3. [DistributedLock.java](E:/Projects/ERP_new/erp-server/erp-common/src/main/java/com/qiheng/erp/common/annotation/DistributedLock.java)：补中文说明，默认值仍30秒。

真实HTTP请求等待供应商行锁35000毫秒后，产品锁剩余TTL25160毫秒，竞争线程无法取得；成功和异常返回后均能重新取得。相关272项回归通过，包括新增5项切面单元测试及23项真实数据库/Redis测试。当前检查未发现其他新增阻塞问题，不保证生产环境永远没有死锁或投递遗漏。

验证证据：[真实HTTP与续约](E:/Projects/ERP_new/_logs/score-log-watchdog-http-retained-review-20261007.md)、[真实MQ执行](E:/Projects/ERP_new/_logs/score-log-watchdog-mq-retained-20261007.log)、[272项回归](E:/Projects/ERP_new/_logs/score-log-watchdog-related-final-20261007.log)、[最终只读数据库核对](E:/Projects/ERP_new/_logs/score-retained-db-final-readonly-20261007.log)。

全后端检查此前发现的AI接口测试HTTP404/200差异，不属于本次评分修复，未修改或隐藏；评分相关回归通过不代表全仓测试全部通过。
