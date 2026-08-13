# 统一退货模块接口与模块边界

## 路由

退货数据由独立模块 `erp-return` 统一提供。所有请求使用 `returnType` 明确业务方向，避免通过前端页面或单号前缀推断来源。

| 接口 | 说明 | 当前状态 |
| --- | --- | --- |
| `GET /returns?returnType=PURCHASE_RETURN` | 分页查询采购退货单 | 已实现 |
| `GET /returns?returnType=SALES_RETURN` | 分页查询销售退货单 | 销售来源提供者未部署时返回明确业务错误 |
| `GET /returns/{returnOrderId}` | 查询退货单详情 | 已实现；按单据类型校验权限 |
| `POST /returns`、`PUT /returns/{returnOrderId}`、`DELETE /returns/{returnOrderId}` | 新建、编辑、删除统一退货草稿 | 已定义契约，后端待实现 |
| `POST /returns/{returnOrderId}/submit`、`approve`、`cancel` | 提交、审核、取消统一退货单 | 已定义契约，后端待实现 |
| `GET /returns/source-orders?returnType=PURCHASE_RETURN` | 搜索可退采购来源订单 | 已实现 |
| `GET /returns/source-orders/{sourceOrderId}/items?returnType=PURCHASE_RETURN` | 查询来源订单可退明细 | 已实现 |
| `GET /returns/source-orders/{sourceOrderId}/items?returnType=SALES_RETURN` | 查询销售来源可退明细 | 等销售模块提供来源适配器后启用 |

列表参数：`returnType`、`returnNo`、`sourceOrderNo`、`partyId`、`warehouseId`、`status`、`pageNum`、`pageSize`。

`/purchase/returns` 与 `/sales/returns` 不是正式接口，已从 OpenAPI 移除。采购、销售两个前端入口均调用 `/returns`，仅由各自 API 适配器固定注入不同的 `returnType`。

## 来源扩展契约

`erp-return` 内的 `returnorder.domain.port` 包包含下列来源契约；契约本身不依赖 Spring、MyBatis 或采购/销售实现：

- `ReturnType`
- `ReturnSourceProvider`
- `ReturnSourceOrder`
- `ReturnSourceItem`

采购、销售模块均应实现各自的 `ReturnSourceProvider` 并单向依赖 `erp-return`；统一退货服务会要求每个 `returnType` 恰好匹配一个来源提供者：缺失或重复配置都会直接失败，绝不创建无法闭环的退货数据。来源适配器返回的履约数量统一为 `BIGINT` 的“×100”存储值，数量精度必须取来源业务明细的 `quantity_precision` 快照，禁止优先读取当前 `product.quantity_precision`。

## 仓储回写

仓储模块通过 `OutboundSourceWritebackPort` 发出出库确认事件。采购退货出库确认时，`ReturnOutboundWritebackPort` 在同一事务内回写 `return_order_item.processed_qty`，并将主单更新为：

- 全部审核数量已处理：`COMPLETED`
- 仅处理部分审核数量：`PARTIAL_EXECUTED`

入库侧也改为要求来源回写适配器唯一匹配，防止此前 `findFirst()` 静默选择第一个实现类而漏写来源单。
