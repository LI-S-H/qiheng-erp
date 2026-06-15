# MVP 客户销售库表设计：极简版

## 设计目标

销售模块先支撑客户维护、销售订单创建、库存锁定、销售出库溯源和销售汇总查询。MVP 阶段不单独设计销售出库单表，销售出库统一使用仓库模块的出入库流水 `stock_bill` / `stock_bill_item`，类型为 `SALES_OUT`。

## 简化原则

- MVP 设计 3 张表：`customer`、`sales_order`、`sales_order_item`。
- 销售出库不在销售模块单独建表，统一使用仓库模块 `stock_bill` / `stock_bill_item`。
- 销售订单主表冗余客户、仓库名称，明细冗余产品信息，减少列表查询联表。
- 销售退货后续复用仓库模块 `SALES_RETURN` 出入库流水；如果退货流程复杂，再补销售退货单表。
- MVP 暂不设计收款单、发票、对账、复杂价格策略、审批流。
- 评分和百分率字段如后续加入，统一遵守 `database-design-conventions.md`：用 `int` 存放大 100 倍后的整数。
- 销售订单明细是订单事实明细，不使用 `deleted`；删除草稿明细时直接物理删除，已审核订单通过订单状态控制。

## 表：customer（客户表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 客户ID |
| customer_code | varchar(64) | 客户编码，唯一 |
| customer_name | varchar(200) | 客户名称 |
| contact_name | varchar(100) | 联系人 |
| contact_phone | varchar(32) | 联系电话 |
| address | varchar(255) | 地址 |
| credit_limit | decimal(18,2) | 信用额度，MVP 先仅记录不做强拦截 |
| status | tinyint | 状态：1 启用，0 禁用 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |
| remark | varchar(500) | 备注 |

关系说明：`sales_order.customer_id` 关联本表。客户手机号属于敏感字段，后续字段权限完善后需要支持脱敏。

## 表：sales_order（销售订单主表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 销售订单ID |
| sales_no | varchar(64) | 销售单号，唯一 |
| customer_id | bigint | 客户ID |
| customer_code | varchar(64) | 客户编码，冗余 |
| customer_name | varchar(200) | 客户名称，冗余 |
| warehouse_id | bigint | 出库仓库ID |
| warehouse_name | varchar(100) | 出库仓库名称，冗余 |
| status | varchar(32) | 状态：`DRAFT`、`SUBMITTED`、`APPROVED`、`PARTIAL_OUTBOUND`、`OUTBOUND_DONE`、`CANCELLED` |
| total_amount | decimal(18,2) | 订单总金额 |
| expected_delivery_date | date | 预计发货日期 |
| locked_at | datetime | 库存锁定时间 |
| created_by_id | bigint | 创建人ID |
| created_by_name | varchar(100) | 创建人姓名 |
| submitted_at | datetime | 提交时间 |
| approved_by_id | bigint | 审核人ID |
| approved_by_name | varchar(100) | 审核人姓名 |
| approved_at | datetime | 审核时间 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |
| remark | varchar(500) | 备注 |

关系说明：销售订单审核后，可生成仓库模块 `stock_bill`，其中 `source_type = SALES_ORDER`，`source_id = sales_order.id`，`source_no = sales_order.sales_no`。

## 表：sales_order_item（销售订单明细表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 明细ID |
| sales_order_id | bigint | 销售订单ID |
| sales_no | varchar(64) | 销售单号，冗余 |
| product_id | bigint | 产品ID |
| product_code | varchar(64) | 产品编码，冗余 |
| product_name | varchar(200) | 产品名称，冗余 |
| unit_name | varchar(32) | 单位名称，冗余 |
| quantity | decimal(18,4) | 销售数量 |
| locked_qty | decimal(18,4) | 已锁定库存数量 |
| outbound_qty | decimal(18,4) | 已出库数量 |
| unit_price | decimal(18,2) | 销售单价 |
| total_amount | decimal(18,2) | 明细金额 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| remark | varchar(500) | 备注 |

关系说明：仓库出入库流水明细 `stock_bill_item.source_item_id` 关联本表，用于从销售出库动作追溯到销售订单明细。

## 表间关系

- `sales_order.customer_id` -> `customer.id`
- `sales_order.warehouse_id` -> `warehouse.id`
- `sales_order_item.sales_order_id` -> `sales_order.id`
- `sales_order_item.product_id` -> `product.id`
- `stock_bill.source_id` -> `sales_order.id`，当 `source_type = SALES_ORDER`
- `stock_bill_item.source_item_id` -> `sales_order_item.id`，当 `stock_bill.bill_type = SALES_OUT`

## MVP 业务规则

- 销售订单只能选择启用状态的客户和产品。
- 销售订单保存后不直接扣减库存。
- 提交或审核销售订单时，服务层校验可用库存 `warehouse_stock.stock_qty - warehouse_stock.locked_qty`。
- 库存锁定成功后，更新 `warehouse_stock.locked_qty` 和 `sales_order_item.locked_qty`。
- 销售订单审核后生成仓库模块 `SALES_OUT` 出入库流水草稿。
- 确认销售出库后，扣减 `warehouse_stock.stock_qty` 和 `warehouse_stock.locked_qty`，并回写 `sales_order_item.outbound_qty`。
- 当明细 `outbound_qty < quantity` 时订单为 `PARTIAL_OUTBOUND`，全部出库后为 `OUTBOUND_DONE`。
- 取消未出库订单时，需要释放已锁定库存。
- 销售退货后续使用 `SALES_RETURN` 入库流水；如需退货申请、退款、质检等复杂流程，再补销售退货单表。

## 测试场景

- 可以新增、编辑、停用客户。
- 可以创建销售订单并选择客户、仓库和产品明细。
- 销售订单提交或审核时可以锁定库存。
- 库存不足时不能锁定库存。
- 审核销售订单后能生成 `SALES_OUT` 出入库流水草稿。
- 确认出库后能扣减库存、释放锁定库存，并回写销售明细已出库数量。
- 销售出库流水可以通过 `source_id/source_no` 反查销售订单。
- AI 销售汇总 Tool 可以按销售订单和销售明细统计销售金额、销售数量。
