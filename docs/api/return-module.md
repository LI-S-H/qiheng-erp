# 统一退货模块接口与模块边界

## 当前实现状态

退货能力已由独立模块 `erp-return` 完整提供，并已接入采购、销售和仓储模块。采购、销售页面分别固定业务方向，但所有后端请求统一使用 `/returns` 与 `returnType`；不得通过页面路径或单号前缀推断业务来源。

| 接口 | 说明 | 当前状态 |
| --- | --- | --- |
| `GET /returns?returnType=PURCHASE_RETURN` | 分页查询采购退货单 | 已实现 |
| `GET /returns?returnType=SALES_RETURN` | 分页查询销售退货单 | 已实现 |
| `GET /returns/{returnOrderId}` | 查询退货单详情 | 已实现，按实际退货类型校验 `return:query` 权限 |
| `POST /returns`、`PUT /returns/{returnOrderId}`、`DELETE /returns/{returnOrderId}` | 新建、编辑、删除统一退货草稿 | 已实现；编辑支持 `DRAFT`、`SUBMITTED`，删除仅限 `DRAFT` |
| `POST /returns/{returnOrderId}/submit`、`approve`、`cancel` | 提交、审核、取消统一退货单 | 已实现；提交幂等，审核与取消使用乐观锁和事务 |
| `GET /returns/source-orders?returnType=PURCHASE_RETURN` | 搜索可退采购来源订单 | 已实现 |
| `GET /returns/source-orders?returnType=SALES_RETURN` | 搜索可退销售来源订单 | 已实现 |
| `GET /returns/source-orders/{sourceOrderId}/items?returnType=PURCHASE_RETURN` | 查询采购来源订单的可退明细 | 已实现 |
| `GET /returns/source-orders/{sourceOrderId}/items?returnType=SALES_RETURN` | 查询销售来源订单的可退明细 | 已实现 |

列表参数为 `returnType`、`returnNo`、`sourceOrderNo`、`partyId`、`warehouseId`、`status`、`pageNum`、`pageSize`。`partyId` 在采购退货中表示供应商 ID，在销售退货中表示客户 ID；所有有效筛选条件按 AND 组合。

`/purchase/returns` 与 `/sales/returns` 是前端页面路由，不是后端接口。两个页面的 API adapter 都调用 `/returns`，并分别固定注入 `PURCHASE_RETURN` 与 `SALES_RETURN`。

## 来源与库存边界

`erp-return` 的 `returnorder.domain.port` 包含 `ReturnType`、`ReturnSourceProvider`、`ReturnSourceOrder` 和 `ReturnSourceItem`。采购、销售模块均已实现各自的 `ReturnSourceProvider`，统一退货服务要求每个 `returnType` 恰好匹配一个来源提供者；缺失或重复注册会拒绝请求，避免创建无法闭环的退货数据。

来源明细数量统一以 `BIGINT ×100` 在内部传递，数量精度读取来源订单明细快照。查询响应中的 `sourceFulfilledQty`、`occupiedQty`、`stockAvailableQty` 与 `availableReturnQty` 都由服务端计算：

- 销售退货：`availableReturnQty = max(0, sourceFulfilledQty - occupiedQty)`，不受当前库存限制；服务端跳过库存查询，因此 `stockAvailableQty` 当前返回 `0`，且不参与退货资格计算。前端必须以 `availableReturnQty` 判断是否可申请。
- 采购退货：`availableReturnQty = max(0, min(sourceFulfilledQty - occupiedQty, stockAvailableQty))`，其中 `stockAvailableQty = warehouse_stock.stock_qty - warehouse_stock.locked_qty`。

草稿不占用可退额度；提交后按申请数量占用，审核后按审核数量占用。提交与审核都会锁定同一来源订单并重新聚合校验，前端显示值不能作为最终依据。

## 仓储执行与回写

审核通过不会直接生成库存事实，而是生成来源工作单：

- 采购退货生成 `PURCHASE_RETURN`、`SOURCE_GENERATED` 的待确认出库单，并对未处理审核数量预占实物库存。
- 销售退货生成 `SALES_RETURN`、`SOURCE_GENERATED` 的待确认入库单，不预占库存。

仓储确认采购退货出库后，通过 `PurchaseReturnOutboundWritebackPort` 回写 `return_order_item.processed_qty`；确认销售退货入库后，通过 `SalesReturnInboundWritebackPort` 回写同一字段。所有审核数量已处理时主单变为 `COMPLETED`，否则为 `PARTIAL_EXECUTED`。采购退货取消尚未执行的已审核单时，会在同一事务内取消待确认出库单并释放预占库存；销售退货取消时同步取消待确认入库单。

具体请求与响应字段、错误码及权限以 [erp-openapi.yaml](erp-openapi.yaml) 为权威契约。
