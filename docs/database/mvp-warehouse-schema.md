# MVP 仓库库存库表设计：入库单/出库单/库存流水分层版

## 设计目标

仓库库存模块先支撑采购入库、销售出库、退货、库存调整和库存查询。本版将“待仓库处理的业务单据”和“已经改变库存的库存事实”拆开：

- `inbound_bill` / `inbound_bill_item` 表示入库单，承接采购入库、销售退货入库和调整入库。
- `outbound_bill` / `outbound_bill_item` 表示出库单，承接销售出库、采购退货出库和调整出库。
- `stock_bill` / `stock_bill_item` 表示确认后的库存流水凭证，只记录已经发生库存变化的事实。
- 采购订单或销售订单审核后只生成 `PENDING_CONFIRM` 的入库单/出库单，不直接写入库存流水，也不改变 `warehouse_stock.stock_qty`。
- 仓库人员在入库单/出库单中手工填写或确认本次数量后，系统才生成库存流水、更新库存余额，并回写来源单据明细的累计已入库/已出库数量。

## 简化原则

- MVP 设计 8 张表：`warehouse`、`warehouse_stock`、`inbound_bill`、`inbound_bill_item`、`outbound_bill`、`outbound_bill_item`、`stock_bill`、`stock_bill_item`。
- 暂不设计库位、批次、序列号、保质期和物流模块；是否到货/是否可出库先由仓库人员在线下确认后录入本次数量。
- 入库单和出库单允许分批处理，同一采购订单或销售订单可以生成多张确认后的库存流水。
- 不同产品单位不得在列表或摘要中直接汇总数量；只有同单位明细才允许展示合计数量。
- 数量字段统一为 `bigint`，数据库按 100 倍整数存储，接口和前端使用真实业务值；来源业务单的数量精度必须读取来源明细快照，不得回查当前产品主数据覆盖历史规则。

## 接口资源边界

- `/warehouse/inbound-bills` 与 `/warehouse/outbound-bills` 表示仓库待处理的入库工作单和出库工作单。列表、新建均按方向访问对应集合资源；前端不得再通过 `POST /warehouse/stock-bills` 兼容入口创建工作单。
- `/warehouse/inbound-bills/{inboundBillId}` 表示单张可写入库单的详情、编辑、提交、确认和取消动作，只访问入库单及其明细；`/warehouse/outbound-bills/{outboundBillId}` 承载对应出库动作，只访问出库单及其明细。两类单据不得再通过同一个 ID 路径混合访问。
- `/warehouse/stock-bills` 表示工作单确认后生成的库存流水凭证，仅用于查询已经发生的库存变化事实，不承担草稿新建、编辑、提交或取消职责。
- 入库类型 `PURCHASE_IN`、`SALES_RETURN`、`ADJUST_IN` 新建时提交到 `/warehouse/inbound-bills`；出库类型 `SALES_OUT`、`PURCHASE_RETURN`、`ADJUST_OUT` 新建时提交到 `/warehouse/outbound-bills`。
- 入库单、出库单响应应使用工作单字段（计划数量、已处理数量、待处理数量和流程状态）；库存流水响应应使用事实字段（变动前数量、变动数量、变动后数量和确认信息），不得用同一个 DTO 混合两类语义。
- 确认工作单时由后端在同一事务内生成只读库存流水并更新库存余额。前端只提交工作单确认动作，不单独创建或修改库存流水。

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
| create_time | datetime | 创建时间 |
| update_time | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |
| remark | varchar(500) | 备注，选填；新增请求可省略，落库默认空字符串 |

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
| stock_qty | bigint | 当前库存数量，按 100 倍整数存储 |
| locked_qty | bigint | 锁定库存数量，按 100 倍整数存储；销售单、采购退货单审核时预占，调整出库创建时预占，出库确认时扣减 |
| create_time | datetime | 创建时间 |
| update_time | datetime | 更新时间 |

关系说明：唯一约束 `(warehouse_id, product_id)`，一个仓库内一个产品只有一条库存余额。

## 表：inbound_bill（入库单主表）

| 字段                    | 类型           | 说明                                                         |
| --------------------- | ------------ | ---------------------------------------------------------- |
| id                    | bigint PK    | 入库单ID                                                      |
| inbound_no            | varchar(64)  | 入库单号，唯一，建议 `IByyyyMMddNNNN`                                |
| inbound_type          | varchar(32)  | 类型：`PURCHASE_IN`、`SALES_RETURN`、`ADJUST_IN`                |
| source_type           | varchar(32)  | 来源类型：`PURCHASE_ORDER`、`SALES_RETURN_ORDER`、`STOCK_ADJUST`  |
| source_id             | bigint       | 来源单据ID；人工补录可为空                                             |
| source_no             | varchar(64)  | 来源单据号                                                      |
| source_party_id       | bigint       | 来源对象 ID；采购相关单据为供应商，销售相关单据为客户，调整入库为发生调整的仓库                         |
| source_party_name     | varchar(200) | 来源对象名称快照；调整入库为来源仓库名称快照                                      |
| entry_mode            | varchar(32)  | `SOURCE_GENERATED`、`MANUAL_SUPPLEMENT`、`MANUAL_ADJUSTMENT` |
| warehouse_id          | bigint       | 仓库ID                                                       |
| warehouse_name        | varchar(100) | 仓库名称快照                                                     |
| status                | varchar(32)  | `DRAFT`、`PENDING_CONFIRM`、`CONFIRMED`、`CANCELLED`          |
| confirmed_by_id       | bigint       | 确认人ID                                                      |
| confirmed_by_name     | varchar(100) | 确认人姓名                                                      |
| confirmed_at          | datetime     | 确认时间                                                       |
| created_by_id         | bigint       | 创建人ID                                                      |
| created_by_name       | varchar(100) | 创建人姓名                                                      |
| responsible_by_id     | bigint       | 业务负责人ID，确认时填入审核人，可空                            |
| responsible_by_name   | varchar(100) | 业务负责人姓名快照，确认时填入审核人，可空                          |
| create_time           | datetime     | 创建时间                                                       |
| update_time           | datetime     | 更新时间                                                       |
| manual_reason         | varchar(500) | 手工补录或调整原因                                                  |
| remark                | varchar(500) | 备注                                                         |

## 表：inbound_bill_item（入库单明细表）

| 字段                 | 类型           | 说明                                     |
| ------------------ | ------------ | -------------------------------------- |
| id                 | bigint PK    | 明细ID                                   |
| inbound_bill_id    | bigint       | 入库单ID                                  |
| inbound_no         | varchar(64)  | 入库单号冗余                                 |
| source_item_id     | bigint       | 来源明细ID；采购入库关联 `purchase_order_item.id` |
| product_id         | bigint       | 产品ID                                   |
| product_code       | varchar(64)  | 产品编码快照                                 |
| product_name       | varchar(200) | 产品名称快照                                 |
| unit_name          | varchar(32)  | 单位名称快照                                 |
| quantity_precision | tinyint      | 数量小数位快照，0-2                            |
| plan_qty           | bigint       | 来源单据计划数量，例如采购数量，按 100 倍整数存储            |
| processed_qty      | bigint       | 本入库单生成前来源明细累计已入库数量快照                   |
| current_qty        | bigint       | 本次入库数量，仓库人员确认时填写，按 100 倍整数存储           |
| pending_qty        | bigint       | 确认本入库单后来源明细预计剩余未入库数量快照                 |
| qualified_qty      | bigint       | 合格数量，采购入库和销售退货入库使用                     |
| defective_qty      | bigint       | 不合格数量，采购入库和销售退货入库使用                    |
| stock_bill_item_id | bigint       | 确认后生成的库存流水明细ID；未确认为空                   |
| create_time        | datetime     | 创建时间                                   |
| update_time        | datetime     | 更新时间                                   |
| remark             | varchar(500) | 备注                                     |

说明：明细不再维护独立预计到货日期；同一入库单对应同一批到货预期，预计到货日期放在主表。

## 表：outbound_bill（出库单主表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 出库单ID |
| outbound_no | varchar(64) | 出库单号，唯一，建议 `OByyyyMMddNNNN` |
| outbound_type | varchar(32) | 类型：`SALES_OUT`、`PURCHASE_RETURN`、`ADJUST_OUT` |
| source_type | varchar(32) | 来源类型：`SALES_ORDER`、`PURCHASE_RETURN_ORDER`、`STOCK_ADJUST` |
| source_id | bigint | 来源单据ID；人工补录可为空 |
| source_no | varchar(64) | 来源单据号 |
| source_party_id | bigint | 来源对象 ID；销售相关单据为客户，采购相关单据为供应商，调整出库为发生调整的仓库 |
| source_party_name | varchar(200) | 来源对象名称快照；调整出库为来源仓库名称快照 |
| entry_mode | varchar(32) | `SOURCE_GENERATED`、`MANUAL_SUPPLEMENT`、`MANUAL_ADJUSTMENT` |
| warehouse_id | bigint | 仓库ID |
| warehouse_name | varchar(100) | 仓库名称快照 |
| status | varchar(32) | `DRAFT`、`PENDING_CONFIRM`、`CONFIRMED`、`CANCELLED` |
| confirmed_by_id | bigint | 确认人ID |
| confirmed_by_name | varchar(100) | 确认人姓名 |
| confirmed_at | datetime | 确认时间 |
| created_by_id | bigint | 创建人ID |
| created_by_name | varchar(100) | 创建人姓名 |
| responsible_by_id | bigint | 业务负责人ID，确认时填入审核人，可空 |
| responsible_by_name | varchar(100) | 业务负责人姓名快照，确认时填入审核人，可空 |
| create_time | datetime | 创建时间 |
| update_time | datetime | 更新时间 |
| manual_reason | varchar(500) | 手工补录或调整原因 |
| remark | varchar(500) | 备注 |

## 表：outbound_bill_item（出库单明细表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 明细ID |
| outbound_bill_id | bigint | 出库单ID |
| outbound_no | varchar(64) | 出库单号冗余 |
| source_item_id | bigint | 来源明细ID；销售出库关联 `sales_order_item.id` |
| product_id | bigint | 产品ID |
| product_code | varchar(64) | 产品编码快照 |
| product_name | varchar(200) | 产品名称快照 |
| unit_name | varchar(32) | 单位名称快照 |
| quantity_precision | tinyint | 数量小数位快照，0-2 |
| plan_qty | bigint | 来源单据计划数量，例如销售数量，按 100 倍整数存储 |
| processed_qty | bigint | 本出库单生成前来源明细累计已出库数量快照 |
| current_qty | bigint | 本次出库数量，仓库人员确认时填写，按 100 倍整数存储 |
| pending_qty | bigint | 确认本出库单后来源明细预计剩余未出库数量快照 |
| qualified_qty | bigint | 合格数量，采购退货出库使用 |
| defective_qty | bigint | 不合格数量，采购退货出库使用 |
| stock_bill_item_id | bigint | 确认后生成的库存流水明细ID；未确认为空 |
| create_time | datetime | 创建时间 |
| update_time | datetime | 更新时间 |
| remark | varchar(500) | 备注 |

补录单没有关联来源时，`source_item_id`、`plan_qty`、`processed_qty` 和 `pending_qty` 均为 `NULL`，不得以 `0` 代替；只有同时具备来源主表和来源明细关联的工作单，才可把这些字段作为来源数量快照校验。

## 表：stock_bill（库存流水凭证主表）

| 字段                   | 类型           | 说明                                                                                  |
| -------------------- | ------------ | ----------------------------------------------------------------------------------- |
| id                   | bigint PK    | 库存流水凭证ID                                                                            |
| bill_no              | varchar(64)  | 库存流水号，唯一，确认入库单/出库单时生成                                                               |
| bill_type            | varchar(32)  | `PURCHASE_IN`、`SALES_OUT`、`PURCHASE_RETURN`、`SALES_RETURN`、`ADJUST_IN`、`ADJUST_OUT` |
| work_bill_id         | bigint       | 已确认入库单或出库单ID；单据表由 `bill_type` 推导                                            |
| business_source_id   | bigint       | 原业务单据ID；订单id必须写真实主键                                                                 |
| business_source_no   | varchar(64)  | 原业务单据号                                                                              |
| entry_mode           | varchar(32)  | 来源工作单的录入方式快照：`SOURCE_GENERATED`、`MANUAL_SUPPLEMENT`、`MANUAL_ADJUSTMENT`             |
| warehouse_id         | bigint       | 仓库ID                                                                                |
| warehouse_name       | varchar(100) | 仓库名称快照                                                                              |
| confirmed_by_id      | bigint       | 确认人ID                                                                               |
| confirmed_by_name    | varchar(100) | 确认人姓名                                                                               |
| confirmed_at         | datetime     | 确认时间                                                                                |
| create_time          | datetime     | 创建时间                                                                                |
| remark               | varchar(500) | 备注                                                                                  |

## 表：stock_bill_item（库存流水凭证明细表）

| 字段                      | 类型           | 说明                    |
| ----------------------- | ------------ | --------------------- |
| id                      | bigint PK    | 明细ID                  |
| bill_id                 | bigint       | 库存流水凭证ID              |
| work_bill_item_id       | bigint       | 已确认入库单明细或出库单明细ID；明细表由主表 `bill_type` 推导 |
| business_source_item_id | bigint       | 原业务来源明细ID             |
| product_id              | bigint       | 产品ID                  |
| product_code            | varchar(64)  | 产品编码快照                |
| product_name            | varchar(200) | 产品名称快照                |
| unit_name               | varchar(32)  | 单位名称快照                |
| quantity_precision      | tinyint      | 数量小数位快照，0-2           |
| qualified_qty           | bigint       | 合格数量，入库质检使用；前端按独立列展示  |
| defective_qty           | bigint       | 不合格数量，入库质检使用；前端按独立列展示 |
| before_qty              | bigint       | 变动前库存                 |
| change_qty              | bigint       | 库存变动数量，入库为正，出库为负；绝对值等于本次工作单数量 |
| after_qty               | bigint       | 变动后库存                 |
| create_time             | datetime     | 创建时间                  |
| remark                  | varchar(500) | 备注                    |

## 表间关系

- `warehouse_stock.warehouse_id` -> `warehouse.id`
- `warehouse_stock.product_id` -> `product.id`
- `inbound_bill.warehouse_id` -> `warehouse.id`
- `inbound_bill_item.inbound_bill_id` -> `inbound_bill.id`
- `outbound_bill.warehouse_id` -> `warehouse.id`
- `outbound_bill_item.outbound_bill_id` -> `outbound_bill.id`
- `stock_bill.work_bill_id` 根据 `bill_type` 指向 `inbound_bill.id` 或 `outbound_bill.id`；`PURCHASE_IN`、`SALES_RETURN`、`ADJUST_IN` 对应入库单，其余类型对应出库单
- `stock_bill_item.work_bill_item_id` 根据主表 `bill_type` 指向入库单明细或出库单明细
- `inbound_bill_item.source_item_id` 可指向 `purchase_order_item.id` 或 `return_order_item.id`（销售退货）
- `outbound_bill_item.source_item_id` 可指向 `sales_order_item.id` 或 `return_order_item.id`（采购退货）

## MVP 业务规则

- 采购订单提交和审核时，单头预计到货日期不能为空；采购订单明细不再维护独立预计到货日期。
- 存在 `warehouse_stock` 库存余额或入库单、出库单、库存流水时禁止删除仓库，后端返回 `409 Conflict`。
- 采购订单审核通过后生成 `PURCHASE_IN` 入库单，状态为 `PENDING_CONFIRM`，带入供应商、入库仓库、采购数量和生成本单前已入库数量；本次入库数量初始为 0 或空业务值，由仓库人员在实物到货验收时填写，确认本单后剩余未入库数量由后端按采购数量、累计已入库和本次入库数量计算；此时不改变库存。
- 销售订单审核通过后生成 `SALES_OUT` 出库单，状态为 `PENDING_CONFIRM`，带入客户、出库仓库、销售数量和生成本单前已出库数量；本次出库数量初始为 0 或空业务值，由仓库人员在确认可出库时填写，确认本单后剩余未出库数量由后端按销售数量、累计已出库和本次出库数量计算；此时不扣减库存。
- 来源采购、销售或退货单据审核通过后生成的是待确认入库单/出库单，不是草稿；只有人工补录和库存调整从仓库页面新增时才先进入 `DRAFT`。
- 由于 MVP 暂无物流模块，系统不自动判断货物是否已到达；仓库人员只能在实物到货或确认可出库后，手工填写本次入库/出库数量并确认。
- 入库单/出库单允许分批处理：确认本次数量小于剩余数量时，来源订单进入 `PARTIAL_INBOUND` 或对应销售部分出库状态；剩余数量可继续生成下一张待确认入库单/出库单。
- 手工补录和库存调整新建后先为 `DRAFT`，通过提交确认动作进入 `PENDING_CONFIRM`；提交确认不改变库存，只表示单据进入仓库确认队列。
- `DRAFT` 和 `PENDING_CONFIRM` 状态允许编辑，但普通采购/销售创建人只能编辑草稿；草稿可修改仓库、手工来源信息、产品明细、本次数量、合格数量、不合格数量和备注，系统生成单据不能增删或更换产品；单据提交后需要具备审核/仓库确认权限的用户才能编辑本次数量、合格数量、不合格数量和备注，仓库、来源信息和产品结构锁定。
- 提交或确认入库单/出库单前，前端必须强制展示完整详情和全部产品明细，并从详情页发起二次确认；列表操作不得直接执行提交或确认。
- `CONFIRMED` 后不允许任何修改或取消；发现错误时必须通过反向入库/出库或库存调整纠正，保留完整流水链路。
- 确认入库单时，后端必须在同一事务内锁定入库单、库存余额和来源采购明细，生成 `stock_bill` / `stock_bill_item`，固化 `entry_mode`、`business_source_id/no` 和审计快照；来源业务类型由 `bill_type` 推导，更新 `warehouse_stock.stock_qty`，并累加 `purchase_order_item.inbound_qty`。
- 确认出库单时，后端必须在同一事务内锁定出库单、库存余额和来源销售明细，生成 `stock_bill` / `stock_bill_item`，固化 `entry_mode`、`business_source_id/no` 和审计快照；来源业务类型由 `bill_type` 推导。销售出库、采购退货出库和调整出库确认时均同步扣减 `warehouse_stock.stock_qty`、`locked_qty`；锁定量不足必须整体回滚，不能改用可用库存绕过预占。
- 销售订单审核占用库存时只更新 `warehouse_stock.locked_qty`；采购退货审核通过时，必须与生成来源工作单在同一事务内按 `approved_qty - processed_qty` 汇总校验可用库存并增加 `locked_qty`。仅调整出库创建时按明细 `current_qty` 校验可用库存并增加 `locked_qty`，编辑草稿或待确认单时调整本单锁定量，取消时释放本单锁定量；销售出库和采购退货出库消费来源单审核时已占用的锁定库存。系统生成采购退货单只能由来源业务取消，并与来源状态一起释放未处理的锁定量。
- 新增手工补录使用 `entry_mode=MANUAL_SUPPLEMENT` 时，前端必须搜索选择已有来源单据并提交 `source_id`、原业务单号和补录原因；调整单据不关联来源单。
- 库存调整使用 `entry_mode=MANUAL_ADJUSTMENT`，只允许 `ADJUST_IN` 或 `ADJUST_OUT`，调整原因必填，`source_party_id/name` 保存受影响仓库 ID 和名称快照；库存调整是单仓库余额增减，不自动生成反向入库单或出库单，跨仓移动应由后续库存调拨单承载。
- 所有手工补录和库存调整的 `responsible_by_id/name` 必须由后端根据当前登录用户写入，前端只读展示且不得提交或代填。
- 产品数量必须符合 `product.quantity_precision`；离散单位精度为 0 时，本次数量、合格数量和不合格数量均只能为整数。
- 确认和取消接口必须幂等；并发状态冲突由后端返回 `409 Conflict`。
- 可用库存由服务层计算：`stock_qty - locked_qty`。
- `warehouse_stock` 不增加单一库存状态字段；库存健康和占用情况在查询时根据数量派生，避免状态值与库存事实不一致。

## 测试场景

- 采购订单审核后，只能看到待确认入库单，库存余额和库存流水不会立即变化。
- 入库单列表列名使用“入库量”，业务对象主列统一显示供应商、客户或调整单的来源仓库名称，只显示本次入库数量摘要；商品展开行与详情必须按字段独立显示采购数量、生成本单前累计已入库、本次入库数量、合格数量、不合格数量和确认本单后剩余未入库数量，非质检适用类型的合格/不合格列显示为不适用。
- 入库单确认后，库存增加，库存流水生成，采购订单明细累计已入库数量增加。
- 部分入库时采购订单显示部分入库，剩余数量可继续生成下一张入库单。
- 销售订单审核后，只能看到待确认出库单，库存余额不会立即扣减。
- 出库单列表列名使用“出库量”，业务对象主列统一显示客户、供应商或调整单的来源仓库名称，只显示本次出库数量摘要；商品展开行与详情必须按字段独立显示销售数量、生成本单前累计已出库、本次出库数量和确认本单后剩余未出库数量；为保持入库/出库页面列结构一致，合格/不合格列可显示为不适用。
- 出库单确认后，库存减少，库存流水生成，销售订单明细累计已出库数量增加。
- 已确认入库单、出库单和库存流水都不能编辑或取消。
- 草稿和待确认入库单/出库单的实际库存变动数量为 0；库存流水只保留已确认事实。
- 库存列表可以按仓库、产品编码、产品名称、库存健康和占用情况组合查询。
- AI 查询库存时读取 `warehouse_stock`，并校验 `ai:query:stock` 或 `warehouse:query` 权限。
