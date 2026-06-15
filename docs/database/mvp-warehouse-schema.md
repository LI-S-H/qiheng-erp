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
| stock_qty | bigint | 当前库存数量，按 100 倍整数存储，例如 12.50 存为 1250 |
| locked_qty | bigint | 锁定库存数量，按 100 倍整数存储，销售单占用时使用 |
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
| entry_mode | varchar(32) | 录入方式审计分类：`SOURCE_GENERATED`、`MANUAL_SUPPLEMENT`、`MANUAL_ADJUSTMENT`；用于追溯业务来源，不另设冗余“是否手工”字段 |
| warehouse_id | bigint | 仓库ID |
| warehouse_name | varchar(100) | 仓库名称，冗余 |
| status | varchar(32) | 状态：`DRAFT`、`CONFIRMED`、`CANCELLED` |
| confirmed_by_id | bigint | 确认人ID |
| confirmed_by_name | varchar(100) | 确认人姓名 |
| confirmed_at | datetime | 确认时间 |
| created_by_id | bigint | 创建人ID |
| created_by_name | varchar(100) | 创建人姓名 |
| responsible_by_id | bigint | 业务负责人ID；手工单据由后端取当前登录用户 |
| responsible_by_name | varchar(100) | 业务负责人姓名快照 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| manual_reason | varchar(500) | 手工补录或库存调整原因；来源生成凭证为空 |
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
| quantity_precision | tinyint | 产品数量小数位快照，0-2 |
| quantity | bigint | 本次出入库数量，正数，按 100 倍整数存储 |
| qualified_qty | bigint | 合格数量，按 100 倍整数存储，采购入库和销售退货入库时用于质量统计 |
| defective_qty | bigint | 不合格数量，按 100 倍整数存储，采购入库和销售退货入库时用于质量统计 |
| before_qty | bigint | 变动前库存，按 100 倍整数存储 |
| change_qty | bigint | 库存变动数量，入库为正，出库为负，按 100 倍整数存储 |
| after_qty | bigint | 变动后库存，按 100 倍整数存储 |
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
- 出入库流水号由后端统一生成，并由 `uk_stock_bill_no` 唯一索引兜底。正常采购、销售和退货流水由对应业务单据生成，`entry_mode=SOURCE_GENERATED`。
- 当采购、销售或退货业务因线下操作、系统故障等原因遗漏登记时，允许在出入库页面手工补录四类业务凭证，`entry_mode=MANUAL_SUPPLEMENT`。补录必须填写原业务单号和补录原因，`source_id` 为空，`source_type` 按出入库类型推导；列表按普通来源单据展示，详情保留录入方式、补录原因和负责人用于追溯。
- 库存调整使用 `entry_mode=MANUAL_ADJUSTMENT`，仅允许 `ADJUST_IN`、`ADJUST_OUT`，来源类型固定为 `STOCK_ADJUST`，调整单号由后端生成，调整原因必填。列表仅对该模式显示低权重“人工调整”提示，避免给正常来源凭证和补录凭证重复堆叠状态标签。
- 所有手工补录和库存调整的 `responsible_by_id/name` 必须由后端根据当前登录用户写入，前端只读展示且不得提交或代填；来源生成凭证的负责人由来源业务单据负责人带入。
- 产品数量必须符合 `product.quantity_precision`；凭证明细保存 `quantity_precision` 快照。数据库统一使用 100 倍整数存储数量，Service 层入库前乘 100、返回接口前除以 100；前端和 OpenAPI 始终使用真实业务值。离散单位精度为 0 时，本次数量、合格数量和不合格数量均只能为整数。
- 出入库状态只允许 `DRAFT -> CONFIRMED` 或 `DRAFT -> CANCELLED`。`DRAFT` 和 `CANCELLED` 不改变库存余额；确认时后端锁定草稿和库存记录，在同一事务内更新 `warehouse_stock`、明细 `before_qty/change_qty/after_qty` 以及采购或销售来源明细的已出入库数量。
- 确认和取消接口必须幂等；重复确认不得再次增减库存，重复取消不得重复写状态。并发状态冲突由后端返回 `409 Conflict`。
- 仅 `DRAFT` 可以编辑。来源业务单据生成的草稿只能维护实际数量、质量数量和备注，不能更换来源、仓库、类型或产品；库存调整草稿允许维护产品明细。`CONFIRMED` 和 `CANCELLED` 均不可编辑。
- 已确认凭证不能直接取消或改回草稿；发现错误时新增相反方向的库存调整凭证纠正，保留原始凭证和完整库存变动链路。
- 出入库记录列表按主表分页，明细数量可按 `stock_bill_item.bill_id` 聚合；不同产品单位不得在列表或摘要中直接汇总数量。
- 可用库存由服务层计算：`stock_qty - locked_qty`。
- `warehouse_stock` 不增加单一库存状态字段；库存健康和占用情况是可同时成立的独立维度，统一在查询时根据数量派生，避免状态值与库存事实不一致。
- 库存健康分为：正常库存（`available_qty > safety_stock_qty`）、低库存（`0 < available_qty <= safety_stock_qty`）、无可用库存（`stock_qty > 0` 且 `available_qty = 0`）、零库存（`stock_qty = 0`）。
- 占用情况分为：未锁定（`locked_qty = 0`）、部分锁定（`0 < locked_qty < stock_qty`）、全部锁定（`stock_qty > 0` 且 `locked_qty = stock_qty`）。
- 其中 `available_qty` 表示服务层计算的 `stock_qty - locked_qty`；库存预警先不落表，并关联 `product.safety_stock_qty` 实时判断。

## 测试场景

- 可以按仓库编码、名称、联系人、联系电话和状态组合查询仓库。
- 可以新增、编辑、启用和停用仓库；新增时展示系统生成提示，编辑时仓库编码保持只读。
- 并发创建仓库时仍由仓库编码唯一索引兜底，后端发生冲突后重新生成，不要求用户处理编码重复。
- 有库存余额或出入库记录的仓库不能删除；无引用仓库可以逻辑删除。
- 采购订单可以生成采购入库流水，确认后库存增加。
- 销售订单可以生成销售出库流水，确认后库存减少。
- 出入库流水明细可以看到每个产品的合格数量、不合格数量、变动前、变动数量、变动后库存。
- 出入库流水可以通过 `source_id/source_no` 反查采购单、销售单或退货单。
- 出入库记录可以按流水号、来源单号、仓库、出入库类型和状态独立查询，多个条件按 AND 组合，并可查看完整产品明细。
- 可以在出入库记录页面新增调整入库、调整出库，或补录遗漏的采购入库、销售出库、采购退货、销售退货草稿；补录记录显示明确标识、原业务单号、原因和负责人。
- 箱、瓶等数量精度为 0 的产品使用输入箭头时按 1 增减，并拒绝小数；可拆分单位按产品设置的小数位增减和校验。
- 草稿可以编辑并确认或取消；已确认和已取消凭证不能编辑，已确认错误使用反向调整纠正。
- 草稿和取消流水的实际变动数量为 0，已确认入库为正数、已确认出库为负数。
- 销售退货和采购退货可以通过同一套出入库流水类型扩展。
- 库存列表可以按仓库、产品编码、产品名称查询。
- 库存列表可以分别按库存健康和占用情况查询；两个维度可组合筛选，并与其他有效条件按 AND 组合。
- 同一条库存可以同时是低库存和部分锁定，也可以同时是无可用库存和全部锁定，页面不得用单一状态互相覆盖。
- 库存摘要只统计记录数、去重仓库数、去重产品数和低库存记录数，不跨单位汇总产品数量。
- AI 查询库存时读取 `warehouse_stock`，并校验 `ai:query:stock` 或 `warehouse:query` 权限。
