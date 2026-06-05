# MVP 供应商采购库表设计：极简版

## 设计目标

采购模块先支撑供应商维护、供应商供货能力维护、采购订单创建和采购入库溯源。为了后续 AI 自动选择本次采购得分最高的供应商，MVP 阶段保留必要的供应商评分字段，但不拆复杂报价、合同、账期、审批流。

## 简化原则

- MVP 设计 4 张表：`supplier`、`supplier_product`、`purchase_order`、`purchase_order_item`。
- 采购入库单不在采购模块单独建表，统一使用仓库模块 `stock_bill` / `stock_bill_item`，类型为 `PURCHASE_IN`。
- `supplier_product` 用来记录“某供应商可以供应某产品”的价格、交期和评分，是 AI 选择供应商的核心基础表。
- 采购订单主表冗余供应商、仓库名称，明细冗余产品信息，减少列表查询联表。
- MVP 暂不设计供应商合同、报价历史、付款单、发票、审批流。
- 采购订单明细是订单事实明细，不使用 `deleted`；删除草稿明细时直接物理删除，已审核订单通过订单状态控制。

## 评分字段存储约定

供应商评分、推荐分、准时率、合格率等百分制字段遵守 `database-design-conventions.md`。默认保留两位小数，但数据库统一用 `int` 存放大 100 倍后的整数，避免 Java 对象转换和小数精度处理问题。

示例：

- `100.00` 存为 `10000`
- `89.75` 存为 `8975`
- `0.00` 存为 `0`

接口展示时再除以 100。金额、库存数量、采购数量仍使用 `decimal`，不适用这个评分约定。

## 表：supplier（供应商表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 供应商ID |
| supplier_code | varchar(64) | 供应商编码，唯一 |
| supplier_name | varchar(200) | 供应商名称 |
| contact_name | varchar(100) | 联系人 |
| contact_phone | varchar(32) | 联系电话 |
| address | varchar(255) | 地址 |
| payment_terms | varchar(100) | 付款条件，先用文本 |
| overall_score | int | 综合评分，放大 100 倍保存，10000 表示 100.00 |
| delivery_score | int | 交付评分，放大 100 倍保存 |
| quality_score | int | 质量评分，放大 100 倍保存 |
| price_score | int | 价格评分，放大 100 倍保存 |
| service_score | int | 服务评分，放大 100 倍保存 |
| avg_delivery_days | decimal(10,2) | 平均交付天数 |
| on_time_rate | int | 准时交付率，放大 100 倍保存，10000 表示 100.00% |
| qualified_rate | int | 到货合格率，放大 100 倍保存，10000 表示 100.00% |
| status | tinyint | 状态：1 启用，0 禁用 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |
| remark | varchar(500) | 备注 |

关系说明：`purchase_order.supplier_id`、`supplier_product.supplier_id` 关联本表。供应商评分字段可人工维护，后续也可以由定时任务根据采购履约数据刷新。

## 表：supplier_product（供应商供货产品表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 供应商供货产品ID |
| supplier_id | bigint | 供应商ID |
| supplier_code | varchar(64) | 供应商编码，冗余 |
| supplier_name | varchar(200) | 供应商名称，冗余 |
| product_id | bigint | 产品ID |
| product_code | varchar(64) | 产品编码，冗余 |
| product_name | varchar(200) | 产品名称，冗余 |
| unit_name | varchar(32) | 单位名称，冗余 |
| supplier_product_code | varchar(100) | 供应商侧产品编码 |
| latest_purchase_price | decimal(18,2) | 最近采购单价 |
| min_order_qty | decimal(18,4) | 最小起订量 |
| lead_time_days | int | 预计交期天数 |
| delivery_score | int | 该产品维度交付评分，放大 100 倍保存 |
| quality_score | int | 该产品维度质量评分，放大 100 倍保存 |
| price_score | int | 该产品维度价格评分，放大 100 倍保存 |
| ai_score | int | AI/规则综合推荐分，放大 100 倍保存 |
| last_purchase_at | datetime | 最近采购时间 |
| status | tinyint | 状态：1 启用，0 禁用 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |
| remark | varchar(500) | 备注 |

关系说明：建议唯一约束 `(supplier_id, product_id)`。采购建议或采购下单时，可以按 `product_id` 找到可供货供应商，再综合 `ai_score`、价格、交期、质量等字段选择供应商。

## 表：purchase_order（采购订单主表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 采购订单ID |
| purchase_no | varchar(64) | 采购单号，唯一 |
| supplier_id | bigint | 供应商ID |
| supplier_code | varchar(64) | 供应商编码，冗余 |
| supplier_name | varchar(200) | 供应商名称，冗余 |
| warehouse_id | bigint | 目标入库仓库ID |
| warehouse_name | varchar(100) | 目标入库仓库名称，冗余 |
| status | varchar(32) | 状态：`DRAFT`、`SUBMITTED`、`APPROVED`、`PARTIAL_INBOUND`、`INBOUND_DONE`、`CANCELLED` |
| total_amount | decimal(18,2) | 订单总金额 |
| expected_arrival_date | date | 预计到货日期 |
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

关系说明：采购订单审核后，可生成仓库模块 `stock_bill`，其中 `source_type = PURCHASE_ORDER`，`source_id = purchase_order.id`，`source_no = purchase_order.purchase_no`。

## 表：purchase_order_item（采购订单明细表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 明细ID |
| purchase_order_id | bigint | 采购订单ID |
| purchase_no | varchar(64) | 采购单号，冗余 |
| supplier_product_id | bigint | 供应商供货产品ID |
| product_id | bigint | 产品ID |
| product_code | varchar(64) | 产品编码，冗余 |
| product_name | varchar(200) | 产品名称，冗余 |
| unit_name | varchar(32) | 单位名称，冗余 |
| quantity | decimal(18,4) | 采购数量 |
| inbound_qty | decimal(18,4) | 已入库数量 |
| unit_price | decimal(18,2) | 采购单价 |
| total_amount | decimal(18,2) | 明细金额 |
| selected_supplier_score | int | 下单时供应商推荐分快照，放大 100 倍保存 |
| expected_arrival_date | date | 明细预计到货日期 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| remark | varchar(500) | 备注 |

关系说明：仓库出入库流水明细 `stock_bill_item.source_item_id` 关联本表，用于从采购入库动作追溯到采购订单明细。

## 表间关系

- `supplier_product.supplier_id` -> `supplier.id`
- `supplier_product.product_id` -> `product.id`
- `purchase_order.supplier_id` -> `supplier.id`
- `purchase_order.warehouse_id` -> `warehouse.id`
- `purchase_order_item.purchase_order_id` -> `purchase_order.id`
- `purchase_order_item.supplier_product_id` -> `supplier_product.id`
- `purchase_order_item.product_id` -> `product.id`
- `stock_bill.source_id` -> `purchase_order.id`，当 `source_type = PURCHASE_ORDER`
- `stock_bill_item.source_item_id` -> `purchase_order_item.id`，当 `stock_bill.bill_type = PURCHASE_IN`

## MVP 业务规则

- 采购订单草稿可以由用户手动创建，也可以后续由 AI 采购建议生成草稿。
- 采购订单只能选择启用状态的供应商和产品。
- 采购明细建议优先选择 `supplier_product` 中评分最高且状态启用的供应商供货产品。
- `selected_supplier_score` 记录下单时的推荐分快照，避免供应商评分后续变化导致历史采购单解释不清。
- 采购订单审核后生成仓库模块 `PURCHASE_IN` 出入库流水草稿，确认入库后更新库存和明细 `inbound_qty`。
- 当明细 `inbound_qty < quantity` 时订单为 `PARTIAL_INBOUND`，全部入库后为 `INBOUND_DONE`。
- 供应商评分字段 MVP 可人工维护；后续通过交付准时率、到货合格率、价格稳定性、售后响应等数据自动刷新。

## 供应商评分刷新规则

供应商评分由 `SupplierScoreRefreshJob` 定时刷新，也可以在采购入库确认后触发局部刷新。MVP 阶段先使用近 180 天已确认的采购入库流水和采购订单数据计算。

推荐计算规则：

- 价格分 `price_score`：同一产品维度下，按最近采购单价与该产品最低采购价对比，业务公式为 `最低采购价 / 当前供应商采购价 * 100`，最高不超过 100；落库时再乘以 100。
- 交付分 `delivery_score`：按采购明细预计到货日期和出入库流水确认时间判断准时率，业务公式为 `准时入库次数 / 总入库次数 * 100`；落库时再乘以 100。
- 质量分 `quality_score`：按出入库流水明细的合格数量和不合格数量计算，业务公式为 `合格数量 / (合格数量 + 不合格数量) * 100`；落库时再乘以 100。
- 综合分 `ai_score`：MVP 先按 `价格分 * 30% + 交付分 * 30% + 质量分 * 30% + 服务分 * 10%` 计算；落库值是最终分数乘以 100，服务分没有自动数据时先取供应商人工维护的 `service_score`。
- 供应商总分 `supplier.overall_score`：按该供应商所有启用 `supplier_product.ai_score` 平均计算。

刷新落库：

- 更新 `supplier_product.latest_purchase_price`、`last_purchase_at`、`price_score`、`delivery_score`、`quality_score`、`ai_score`。
- 汇总更新 `supplier.overall_score`、`delivery_score`、`quality_score`、`price_score`、`on_time_rate`、`qualified_rate`、`avg_delivery_days`。
- 新供应商或暂无采购履约数据时，允许人工维护初始分数，定时任务只刷新已有可计算数据的记录。

## 测试场景

- 可以新增、编辑、停用供应商。
- 可以维护某供应商能供应哪些产品，以及价格、交期、评分。
- 创建采购订单时可以选择供应商、仓库和产品明细。
- 采购明细可以保存供应商推荐分快照。
- 审核采购订单后能生成 `PURCHASE_IN` 出入库流水草稿。
- 确认入库后能更新库存，并回写采购明细已入库数量。
- 确认入库时记录合格数量和不合格数量，供应商评分刷新任务可以据此计算质量分。
- AI 后续可以按产品查询候选供应商，并按 `ai_score` 等字段选择推荐供应商。
