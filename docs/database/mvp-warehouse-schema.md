# MVP 仓库库存库表设计：出入库流水版

## 设计目标

仓库库存模块先支撑采购入库、销售出库、退货、库存调整和库存查询。MVP 阶段不再单独设计库存流水表，而是把出入库单作为库存流水凭证，出入库单明细记录每个产品的库存变动前、变动数量和变动后数量。

## 简化原则

- MVP 设计 4 张表：`warehouse`、`warehouse_stock`、`stock_bill`、`stock_bill_item`。
- `stock_bill` 是出入库流水主表，记录单据类型、来源单据、仓库、状态和确认信息。
- `stock_bill_item` 是出入库流水明细，记录产品、数量、变动前库存、变动后库存。
- 暂不设计单独 `stock_flow` 表，避免出入库单和库存流水重复。
- 暂不设计库位、批次、序列号、保质期。
- 采购入库、销售出库、采购退货、销售退货、库存调整都复用统一出入库流水结构。
- 评分和百分率字段如后续加入，统一遵守 `database-design-conventions.md`：用 `int` 存放大 100 倍后的整数。
- 库存余额和出入库流水属于库存事实数据，不使用 `deleted`；作废或取消通过出入库流水主表 `status` 表达。

## 表：warehouse（仓库表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 仓库ID |
| warehouse_code | varchar(64) | 仓库编码，唯一 |
| warehouse_name | varchar(100) | 仓库名称 |
| contact_name | varchar(100) | 联系人 |
| contact_phone | varchar(32) | 联系电话 |
| address | varchar(255) | 仓库地址 |
| status | tinyint | 状态：1 启用，0 禁用 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |
| remark | varchar(500) | 备注 |

关系说明：`warehouse_stock.warehouse_id`、`stock_bill.warehouse_id` 关联本表。

## 表：warehouse_stock（库存余额表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 库存ID |
| warehouse_id | bigint | 仓库ID |
| warehouse_code | varchar(64) | 仓库编码，冗余 |
| warehouse_name | varchar(100) | 仓库名称，冗余 |
| product_id | bigint | 产品ID |
| product_code | varchar(64) | 产品编码，冗余 |
| product_name | varchar(200) | 产品名称，冗余 |
| unit_name | varchar(32) | 单位名称，冗余 |
| stock_qty | decimal(18,4) | 当前库存数量 |
| locked_qty | decimal(18,4) | 锁定库存数量，销售单占用时使用 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |

关系说明：建议唯一约束 `(warehouse_id, product_id)`，一个仓库里一个产品只有一条库存余额。

## 表：stock_bill（出入库流水主表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 出入库流水ID |
| bill_no | varchar(64) | 出入库流水号，唯一 |
| bill_type | varchar(32) | 类型：`PURCHASE_IN`、`SALES_OUT`、`PURCHASE_RETURN`、`SALES_RETURN`、`ADJUST_IN`、`ADJUST_OUT` |
| source_type | varchar(32) | 来源类型：`PURCHASE_ORDER`、`SALES_ORDER`、`PURCHASE_RETURN_ORDER`、`SALES_RETURN_ORDER`、`STOCK_ADJUST` |
| source_id | bigint | 来源单据ID |
| source_no | varchar(64) | 来源单据号 |
| warehouse_id | bigint | 仓库ID |
| warehouse_name | varchar(100) | 仓库名称，冗余 |
| status | varchar(32) | 状态：`DRAFT`、`CONFIRMED`、`CANCELLED` |
| confirmed_by_id | bigint | 确认人ID |
| confirmed_by_name | varchar(100) | 确认人姓名 |
| confirmed_at | datetime | 确认时间 |
| created_by_id | bigint | 创建人ID |
| created_by_name | varchar(100) | 创建人姓名 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| remark | varchar(500) | 备注 |

关系说明：采购入库来源采购订单，销售出库来源销售订单；后续销售退货、采购退货也复用本表，只是 `bill_type` 和 `source_type` 不同。

## 表：stock_bill_item（出入库流水明细表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 明细ID |
| bill_id | bigint | 出入库流水ID |
| bill_no | varchar(64) | 出入库流水号，冗余 |
| source_item_id | bigint | 来源单据明细ID |
| product_id | bigint | 产品ID |
| product_code | varchar(64) | 产品编码，冗余 |
| product_name | varchar(200) | 产品名称，冗余 |
| unit_name | varchar(32) | 单位名称，冗余 |
| quantity | decimal(18,4) | 本次出入库数量，正数 |
| qualified_qty | decimal(18,4) | 合格数量，采购入库和销售退货入库时用于质量统计 |
| defective_qty | decimal(18,4) | 不合格数量，采购入库和销售退货入库时用于质量统计 |
| before_qty | decimal(18,4) | 变动前库存 |
| change_qty | decimal(18,4) | 库存变动数量，入库为正，出库为负 |
| after_qty | decimal(18,4) | 变动后库存 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| remark | varchar(500) | 备注 |

关系说明：明细通过 `source_item_id` 关联采购订单明细、销售订单明细或退货单明细，便于从库存动作反查业务来源。

## 表间关系

- `warehouse_stock.warehouse_id` -> `warehouse.id`
- `warehouse_stock.product_id` -> `product.id`
- `stock_bill.warehouse_id` -> `warehouse.id`
- `stock_bill_item.bill_id` -> `stock_bill.id`
- `stock_bill_item.product_id` -> `product.id`

## MVP 业务规则

- 仓库编码由后端在创建仓库时统一生成，并由唯一索引保证不重复；前端不提交仓库编码。编码生成后不可修改，避免 `warehouse_stock.warehouse_code` 冗余字段发生大范围级联更新。
- 仓库名称允许修改；修改时后端必须在同一事务内同步当前库存余额表 `warehouse_stock.warehouse_name`，已生成的 `stock_bill.warehouse_name` 继续保留业务发生时的历史快照。
- 停用仓库后不能再用于新建采购、销售、退货或库存调整业务，但历史单据和现有库存余额继续保留并可查询。
- 删除仓库使用逻辑删除；存在 `warehouse_stock` 库存余额或 `stock_bill` 出入库记录时禁止删除，后端返回 `409 Conflict`。
- 采购订单确认后可生成 `PURCHASE_IN` 入库流水，确认后增加库存，并在明细中记录 `qualified_qty`、`defective_qty`、`before_qty`、`change_qty`、`after_qty`。
- 销售订单审核后可生成 `SALES_OUT` 出库流水，确认后扣减库存，并在明细中记录库存变化。
- 销售单占用库存时只更新 `warehouse_stock.locked_qty`；确认出库后再扣减 `stock_qty` 和 `locked_qty`。
- 销售退货后续使用 `SALES_RETURN` 入库流水，确认后增加库存。
- 采购退货后续使用 `PURCHASE_RETURN` 出库流水，确认后扣减库存。
- 库存调整使用 `ADJUST_IN` 或 `ADJUST_OUT` 出入库流水。
- 可用库存由服务层计算：`stock_qty - locked_qty`。
- 库存预警先不落表，查询时用 `warehouse_stock.stock_qty` 和 `product.safety_stock_qty` 判断。

## 测试场景

- 可以按仓库编码、名称、联系人、联系电话和状态组合查询仓库。
- 可以新增、编辑、启用和停用仓库；新增时展示系统生成提示，编辑时仓库编码保持只读。
- 并发创建仓库时仍由仓库编码唯一索引兜底，后端发生冲突后重新生成，不要求用户处理编码重复。
- 有库存余额或出入库记录的仓库不能删除；无引用仓库可以逻辑删除。
- 采购订单可以生成采购入库流水，确认后库存增加。
- 销售订单可以生成销售出库流水，确认后库存减少。
- 出入库流水明细可以看到每个产品的合格数量、不合格数量、变动前、变动数量、变动后库存。
- 出入库流水可以通过 `source_id/source_no` 反查采购单、销售单或退货单。
- 销售退货和采购退货可以通过同一套出入库流水类型扩展。
- 库存列表可以按仓库、产品编码、产品名称查询。
- 库存列表可以按可用、已锁定、低库存和零库存等明确派生状态查询；多个条件按 AND 组合。
- 库存摘要只统计记录数、去重仓库数、去重产品数和低库存记录数，不跨单位汇总产品数量。
- AI 查询库存时读取 `warehouse_stock`，并校验 `ai:query:stock` 或 `warehouse:query` 权限。
