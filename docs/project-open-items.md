# 当前未完成事项

本文只记录代码和设计说明中已确认的未完成事项，避免把过期 `TODO` 注释或未执行的回归清单误当成缺失功能。

## 本轮已验证

1. **采购订单冻结供应商推荐分**：已实现审核事务内取得供应商行锁后冻结当前推荐分；创建和编辑草稿不接受前端分数，无评分保持 `null`，有效零分保留 `0`。本轮通过单元、真实 HTTP 和锁等待测试；前端评分补查失败不阻止编辑。对应代码：[PurchaseOrderServiceImpl.java](../erp-server/erp-purchase/src/main/java/com/qiheng/erp/purchase/service/impl/PurchaseOrderServiceImpl.java)，证据见[本轮修复验证](qa/2026-10-08-remaining-fixes.md)。
2. **库存列表回填安全库存**：已关联产品档案并将整数存储值除以100返回；风险口径与筛选统一，前端不把未知值转换为0。真实数据库筛选、局部加载及三种视口浏览器回归通过。对应代码：[WarehouseStockServiceImpl.java](../erp-server/erp-warehouse/src/main/java/com/qiheng/erp/warehouse/service/impl/WarehouseStockServiceImpl.java)。

## 后续扩展

3. **部门数据范围权限**：当前 `ancestors` 字段暂不参与查询，采购与销售数据尚未按部门范围过滤。只有在业务确定需要隔离部门数据时再实施，不能把字段存在误认为权限已经生效。详见 [采购库表设计](database/mvp-purchase-schema.md) 与 [销售库表设计](database/mvp-sales-schema.md)。

## 已接入的降级与会话隔离

4. **工作台指标比较降级**：已区分 `AVAILABLE`、`NO_BASELINE`、`UNAVAILABLE`。月基线缓存失败尝试数据库回算，回算失败上报系统异常并保留本期值；缓存回填失败不丢弃已计算结果。昨日快照读取失败与正常缺失分别展示不可用、无基线。对应代码：[DashboardMetricsLoader.java](../erp-server/erp-dashboard/src/main/java/com/qiheng/erp/dashboard/loader/DashboardMetricsLoader.java)。
5. **铃铛会话隔离与刷新**：已实现登录、退出清理概览，旧请求不覆盖新会话，旧账号401不注销新账号；后台刷新失败保留旧数据并显示提示。本轮会话单元测试和铃铛浏览器回归通过，故障注入使用 Mock，不代表全部生产权限场景已验收。

## 明确未接入的扩展

6. **采购责任取消评分**：数据库取消原因、责任标记及操作人字段仅为预留；当前取消接口只接收版本号，交付评分不统计取消订单。后续必须明确业务规则后再同时接入接口、页面、评分和测试，不能依据旧规划认定已完成。

## 本轮发现的范围外回归失败

7. **AI 历史接口 HTTP 状态**：`AiAssistantControllerHttpStatusTest.historyReturns404ForMissingOrForeignConversation` 预期 404，当前返回 200。本轮没有修改 AI 模块，也没有关闭或放宽该断言；需另行核对 AI 接口约定与全局异常响应策略，不能把本轮关联测试通过表述为整个仓库全量测试通过。

## 清单维护约定

- 完成事项后同步更新本文，并移除对应的代码 TODO 标记；保留必要的设计说明注释。
- QA 清单里的未勾选项代表尚未记录验收结果，不自动等同于功能未实现。
- `docs/qa/dashboard-bell-regression/checklist.md` 已按当前行为修正 DENIED 权限场景：铃铛保留，待办区域展示空态。
