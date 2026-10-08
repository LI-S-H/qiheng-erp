# MVP 供应商采购库表设计：极简版

## 设计目标

采购模块先支撑供应商维护、供应商供货能力维护、采购订单创建和采购入库溯源。供应商评分以已确认入库事实、有效报价和人工服务分为唯一来源，供后续 AI 自动选择供应商使用。

## 简化原则

- MVP 设计 4 张表：`supplier`、`supplier_product`、`purchase_order`、`purchase_order_item`。
- 采购入库单不在采购模块单独建表，统一使用仓库模块 `inbound_bill` / `inbound_bill_item`，类型为 `PURCHASE_IN`；确认后再生成库存流水 `stock_bill` / `stock_bill_item`。
- `supplier_product` 用来记录“某供应商可以供应某产品”的报价、交期和产品维度评分，是 AI 选择供应商的核心基础表；交付分只属于供应商维度。
- 采购订单主表冗余供应商、仓库名称，明细冗余产品信息，减少列表查询联表。
- 当前不设计供应商合同、报价历史、付款单、发票、审批流；当前有效报价直接维护在 `supplier_product`。
- 采购订单明细是订单事实明细，不使用 `deleted`；删除草稿明细时直接物理删除，已审核订单通过订单状态控制。

## 评分字段存储约定

供应商评分和推荐分统一用 `INT` 保存百分制分数放大 100 倍后的整数。API 只向前端返回 `0~100` 的业务小数；数据库中的 `NULL` 表示样本不足或尚未人工设定，`0` 仅表示已有有效样本但得分为零。

示例：

- `100.00` 存为 `10000`
- `89.75` 存为 `8975`
- `0.00` 存为 `0`，但不能用来表示“未评分”

接口展示时再除以 100。金额、库存数量、采购数量均按各自的放大整数约定保存，不使用浮点数。

## 表：supplier（供应商表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 供应商ID |
| supplier_code | varchar(64) | 供应商编码，唯一 |
| supplier_name | varchar(200) | 供应商名称 |
| contact_name | varchar(100) | 联系人，可选；未维护时保存为空字符串 |
| contact_phone | varchar(32) | 联系电话，可选；未维护时保存为空字符串 |
| address | varchar(255) | 地址，可选；未维护时保存为空字符串 |
| payment_terms | varchar(100) | 付款条件，可选；未维护时保存为空字符串 |
| overall_score | int，可为空 | 综合评分，INT×100；样本不足时为空 |
| delivery_score | int，可为空 | 供应商交付评分，INT×100；样本不足时为空 |
| quality_score | int，可为空 | 供应商质量评分，INT×100；样本不足时为空 |
| price_score | int，可为空 | 供应商价格评分，INT×100；样本不足时为空 |
| service_score | int，可为空 | 人工服务评分，INT×100；未人工设定时为空 |
| service_score_reason | varchar(500) | 当前人工服务分原因；服务分为空时为空字符串，初始值不写评分变化日志但原因必须保留 |
| avg_delivery_days | decimal(10,2)，可为空 | 最近 180 天完全入库订单的金额加权平均到货周期（天），只用于分析 |
| score_basis_amount | bigint | 已确认入库累计金额，单位分；保留现有累加行为供展示，不作为质量分权重 |
| score_status | varchar(16) | `NOT_READY` 样本不足，`READY` 可参与自动推荐 |
| status | tinyint | 状态：1 启用，0 禁用 |
| create_time | datetime | 创建时间 |
| update_time | datetime | 更新时间 |
| updated_by_id | bigint，可为空 | 最后维护人ID；创建、编辑、启停时更新 |
| updated_by_name | varchar(100) | 最后维护人姓名；历史数据为空时前端显示“未记录” |
| deleted | tinyint | 逻辑删除 |
| remark | varchar(500) | 备注 |

关系说明：`purchase_order.supplier_id`、`supplier_product.supplier_id` 关联本表。除 `service_score` 外，评分字段均只能由评分服务写入；服务分必须填写原因并写入 `service_score_reason`。初始服务分只保存当前原因，不写评分变化日志；后续调整在实际评分变化时才写日志。

## 表：supplier_product（供应商供货产品表）

| 字段                    | 类型            | 说明                    |
| --------------------- | ------------- | --------------------- |
| id                    | bigint PK     | 供应商供货产品ID             |
| supplier_id           | bigint        | 供应商ID                 |
| product_id            | bigint        | 产品ID                  |
| latest_purchase_price | bigint，可为空 | 由已确认采购入库事实维护的最近成交单价，单位分 |
| quoted_purchase_price | bigint，可为空 | 人工维护的有效报价，单位分 |
| quoted_price_reason | varchar(500) | 当前人工有效报价原因；报价为空时为空字符串 |
| quoted_price_updated_at | datetime，可为空 | 报价最近维护时间；报价为空时必须为空 |
| quote_valid_until | date，可为空 | 报价有效截止日；报价为空时必须为空 |
| min_order_qty         | bigint        | 最小起订量，放大 100 倍保存，1000 表示 10.00      |
| quality_score         | int，可为空 | 供应产品质量评分，INT×100；样本不足时为空 |
| price_score           | int，可为空 | 供应产品价格评分，INT×100；报价或参考价无效时为空 |
| recommend_score       | int，可为空 | 供应产品推荐评分，INT×100；旧接口字段名仍为 `aiScore`，任一必需输入缺失时为空 |
| last_purchase_at      | datetime，可为空 | 当前最近成交价对应的最后一次已确认采购入库时间 |
| avg_delivery_days     | decimal(10,2)，可为空 | 最近 180 天完全入库采购单的金额加权平均到货周期（天）；由入库确认回写，只用于分析，不可人工填写 |
| score_basis_amount    | bigint | 已确认入库累计金额，单位分；保留现有累加行为供展示，不参与质量分计算 |
| score_status          | varchar(16) | `NOT_READY` 样本不足，`READY` 可参与自动推荐 |
| status                | tinyint       | 状态：1 启用，0 禁用          |
| create_time           | datetime      | 创建时间                  |
| update_time           | datetime      | 更新时间                  |
| updated_by_id         | bigint，可为空 | 最后维护人ID；创建、编辑、启停时更新 |
| updated_by_name       | varchar(100)  | 最后维护人姓名；历史数据为空时前端显示“未记录” |
| deleted               | tinyint       | 逻辑删除                  |
| remark                | varchar(500)  | 备注                    |

关系说明：唯一约束为 `(supplier_id, product_id)`。该表只保存供应商-产品关系及其报价、起订量、平均到货周期和评分等关系属性；供应商编码/名称、产品编码/名称/单位通过 `supplier_id`、`product_id` 关联主数据实时查询，产品编码不可人工维护。平均到货周期只在采购单完全入库时按最近 180 天事实回写，部分入库不计入样本。报价及其原因成对维护；初始报价只保存当前原因，不写评分变化日志。采购建议只从 `status=1`、`deleted=0` 且 `score_status=READY` 的关系中选择候选。

## 表：purchase_order（采购订单主表）

| 字段                    | 类型            | 说明                                                                             |
| --------------------- | ------------- | ------------------------------------------------------------------------------ |
| id                    | bigint PK     | 采购订单ID                                                                         |
| purchase_no           | varchar(64)   | 采购单号，唯一                                                                        |
| supplier_id           | bigint        | 供应商ID                                                                          |
| supplier_code         | varchar(64)   | 供应商编码，冗余                                                                       |
| supplier_name         | varchar(200)  | 供应商名称，冗余                                                                       |
| warehouse_id          | bigint        | 目标入库仓库ID                                                                       |
| warehouse_name        | varchar(100)  | 目标入库仓库名称，冗余                                                                    |
| status                | varchar(32)   | 状态：`DRAFT`、`SUBMITTED`、`APPROVED`、`PARTIAL_INBOUND`、`INBOUND_DONE`、`CANCELLED` |
| total_amount          | bigint        | 订单总金额，按分（×100）保存，84480 表示 844.80                                          |
| expected_arrival_date | date          | 预计到货日期；草稿阶段可为空，提交和审核前必须校验非空                                                    |
| created_by_id         | bigint        | 创建人ID                                                                          |
| created_by_name       | varchar(100)  | 创建人姓名                                                                          |
| submitted_at          | datetime      | 提交时间                                                                           |
| submitted_by_id       | bigint，可为空 | 提交人ID；仅提交动作写入，历史数据不猜测回填 |
| submitted_by_name     | varchar(100)  | 提交人姓名；详情流程记录的提交操作人 |
| approved_by_id        | bigint        | 审核人ID                                                                          |
| approved_by_name      | varchar(100)  | 审核人姓名                                                                          |
| approved_at           | datetime      | 审核时间                                                                           |
| fully_received_at     | datetime，可为空 | 使整张采购单完全入库的最后一笔入库确认时间；不得使用通用 `update_time` 代替 |
| cancelled_at          | datetime，可为空 | 预留取消时间，当前接口未写入 |
| cancel_reason         | varchar(500) | 预留取消原因，当前接口不接收 |
| cancel_affects_delivery_score | tinyint | 预留供应商责任取消标记，当前取消接口与评分引擎未接入 |
| cancelled_by_id / cancelled_by_name | bigint / varchar(100) | 预留取消操作人信息，当前接口未写入 |
| create_time           | datetime      | 创建时间                                                                           |
| update_time           | datetime      | 更新时间                                                                           |
| deleted               | tinyint       | 逻辑删除                                                                           |
| remark                | varchar(500)  | 备注                                                                             |

关系说明：采购订单审核后，可生成仓库模块 `inbound_bill`，其中 `source_type = PURCHASE_ORDER`，`source_id = purchase_order.id`，`source_no = purchase_order.purchase_no`，并快照供应商、入库仓库、采购数量和累计已入库数量。待确认入库单的本次入库数量初始为 0 或空业务值，由仓库人员按实物到货填写；入库确认与采购明细累计数量、采购状态在同一事务内回写。若仍有剩余数量，系统仅在此次入库确认成功后生成一张只含剩余数量的新待确认入库单；编辑阶段不生成新单，已确认入库单不再修改。

## 表：purchase_order_item（采购订单明细表）

| 字段                      | 类型            | 说明                     |
| ----------------------- | ------------- | ---------------------- |
| id                      | bigint PK     | 明细ID                   |
| purchase_order_id       | bigint        | 采购订单ID                 |
| purchase_no             | varchar(64)   | 采购单号，冗余                |
| supplier_product_id     | bigint        | 供应商供货产品ID              |
| product_id              | bigint        | 产品ID                   |
| product_code            | varchar(64)   | 产品编码，冗余                |
| product_name            | varchar(200)  | 产品名称，冗余                |
| unit_name               | varchar(32)   | 单位名称，冗余                |
| quantity_precision      | tinyint       | 数量小数位快照：0-2；创建时从 `product.quantity_precision` 固化，后续不得因产品主数据变更而改写 |
| quantity                | bigint        | 采购数量，放大 100 倍保存，2400 表示 24.00  |
| inbound_qty             | bigint        | 已入库数量，放大 100 倍保存，900 表示 9.00   |
| unit_price              | bigint     | 采购单价，按分（×100）保存，3520 表示 35.20  |
| total_amount            | bigint     | 明细金额，按分（×100）保存，168960 表示 1689.60 |
| selected_supplier_score | int，可为空 | 审核时由后端读取当前供货关系推荐分并冻结，INT×100；未审核或无可用评分为 NULL，有效零分为 0 |
| create_time             | datetime      | 创建时间                   |
| update_time             | datetime      | 更新时间                   |
| remark                  | varchar(500)  | 备注                     |

关系说明：仓库入库单明细 `inbound_bill_item.source_item_id` 关联本表；入库确认后生成的 `stock_bill_item.business_source_item_id` 继续关联本表，用于从库存流水反查采购订单明细。

## 表：supplier_score_change_log（供应商评分变化日志）

每次指标分变化只写一条日志，同时记录受影响的产品推荐分和供应商综合分前后值；不把推荐分、总分再重复作为 `metric_type`。

| 字段 | 类型 | 说明 |
|---|---|---|
| change_key | char(64) | ASCII 幂等键，唯一 |
| batch_no | varchar(64) | 重算或人工操作批次号 |
| rule_version | varchar(32) | 评分规则版本 |
| supplier_id / supplier_product_id | bigint | 供应商及可选的供货关系定位 |
| metric_type | varchar(16) | `PRICE`、`QUALITY`、`DELIVERY`、`SERVICE` |
| metric_score_before / metric_score_after | int，可为空 | 指标分前后值，INT×100 |
| product_recommend_score_before / product_recommend_score_after | int，可为空 | 产品推荐分前后值，INT×100 |
| supplier_overall_score_before / supplier_overall_score_after | int，可为空 | 供应商总分前后值，INT×100 |
| trigger_type | varchar(32) | `PRICE_TRIGGER`、`SERVICE_TRIGGER`、`INBOUND_TRIGGER`、`QUOTE_EXPIRED_TRIGGER`、`DAILY_TRIGGER`、`MERGED` |
| related_sources | JSON，非空 | 完整来源数组，元素包含 `businessType`、字符串 `businessId` 和可空 `businessNo`；无来源为 `[]` |
| operator_type | varchar(16) | `USER` 人工操作 / `SYSTEM` 系统任务（MQ Consumer、D1/D2 定时任务、合并重算） |
| operator_id / operator_name | bigint / varchar(100) | USER 类型填实际用户，operator_name 填姓名；SYSTEM 类型 operator_id 为 NULL，operator_name 填场景描述（如 `D2-每日兜底`） |
| reason | varchar(600) | 人工调整原因或系统计算说明；请求原因最多 500 字，额外空间保存校正说明 |
| create_time | datetime | 创建时间 |

日志来源按业务类型与 ID 去重并确定排序；同一个来源出现冲突编号时拒绝写入，不截断多单列表。同批次各指标日志保存同一份来源快照，查询返回结构化数组而不是 JSON 字符串。来源业务类型仅包含 `PURCHASE_ORDER`、`SUPPLIER_PRODUCT`、`PRODUCT`、`SUPPLIER`。

- 完全入库记录采购单 ID 与采购单号，不能混用入库单号；合并时保留全部采购单。
- 报价修改、清空和到期记录供货关系；该关系没有业务编号，`businessNo` 为 NULL。参考价记录产品 ID 与产品编码，服务分记录供应商 ID 与供应商编码。
- 每日校正来源为 `[]`，校正业务日期放入原因。人工操作保存真实用户 ID 和姓名；SYSTEM 的用户 ID 为 NULL，名称为明确场景。
- 报价调整原因直接取本次请求，清空报价也保留。请求原因上限为 500 字，日志上限为 600 字，额外空间保留校正说明，不能截断人工原因。来源不能塞入原因字段。仅综合分或推荐分校正在对应日志原因中明确标注，不表示基础指标变化。

## 表间关系

- `supplier_product.supplier_id` -> `supplier.id`
- `supplier_product.product_id` -> `product.id`
- `purchase_order.supplier_id` -> `supplier.id`
- `purchase_order.warehouse_id` -> `warehouse.id`
- `purchase_order_item.purchase_order_id` -> `purchase_order.id`
- `purchase_order_item.supplier_product_id` -> `supplier_product.id`
- `purchase_order_item.product_id` -> `product.id`
- `inbound_bill.source_id` -> `purchase_order.id`，当 `source_type = PURCHASE_ORDER`
- `inbound_bill_item.source_item_id` -> `purchase_order_item.id`，当 `inbound_bill.inbound_type = PURCHASE_IN`
- `stock_bill.business_source_id` -> `purchase_order.id`，当 `business_source_type = PURCHASE_ORDER`
- `stock_bill_item.business_source_item_id` -> `purchase_order_item.id`，当 `stock_bill.bill_type = PURCHASE_IN`

## MVP 业务规则

- 采购订单草稿可以由用户手动创建，也可以后续由 AI 采购建议生成草稿。
- 采购订单只能选择启用状态的供应商和产品。
- 采购明细建议优先选择 `supplier_product` 中评分最高且状态启用的供应商供货产品。
- MVP 阶段一张采购订单代表同一批到货承诺，预计到货日期以 `purchase_order.expected_arrival_date` 为来源；采购订单审核生成首张采购入库单及部分入库后生成续单时，复制到 `inbound_bill.expected_arrival_date` 作为只读快照，采购明细不再单独维护预计到货日期。
- 草稿可以暂存为空预计到货日期；提交和审核采购订单时，后端必须重新校验 `expected_arrival_date`、供应商、入库仓库、产品明细、数量和价格均有效。
- `DRAFT` 状态可由创建/采购权限用户编辑；`SUBMITTED` 状态仍允许修改供应商、入库仓库、预计到货日期、备注和明细，但必须由具备审核/审批权限的用户执行，避免普通创建人提交后绕过审核改单。
- 前端状态名称统一按当前业务阶段显示：`DRAFT` 为“草稿”、`SUBMITTED` 为“待审核”、`APPROVED` 为“待入库”、`PARTIAL_INBOUND` 为“部分入库”、`INBOUND_DONE` 为“已入库”、`CANCELLED` 为“已取消”；筛选项、表格标签、详情和摘要不得再使用“已提交”“已审核”表达当前状态。
- `APPROVED` 后采购订单主表和明细不允许直接修改；但尚未发生确认入库事实时可以取消：后端须在同一事务内先校验关联采购入库工作单均未确认，再作废全部 `DRAFT`、`PENDING_CONFIRM` 工作单并将订单置为 `CANCELLED`，不产生库存或库存流水变动。
- `PARTIAL_INBOUND`、`INBOUND_DONE` 已经产生入库事实，不允许直接修改采购订单主表或明细；如需纠正，应通过仓库入库单、冲销或反向业务单据处理。
- `selected_supplier_score` 由后端在审核通过的同一事务内读取当前供货关系 `recommend_score` 并冻结；未审核明细为 NULL，没有可用评分也为 NULL，有效零分保存 0，前端不得提交该字段。后续评分变化不回写已审核单据，历史空快照不使用今天的评分补填。
- 采购订单审核后生成仓库模块 `PURCHASE_IN` 待确认入库单，不直接改变库存，也不生成库存流水；本次入库数量初始为 0 或空业务值，仓库人员按实物到货填写并确认后，才生成 `stock_bill` 库存流水、更新库存和明细 `inbound_qty`。
- 确认采购入库时，后端必须校验本次入库数量大于 0 且不超过来源明细剩余未入库数量；确认后的剩余未入库数量由后端计算，不作为前端提交字段。
- 当明细 `inbound_qty < quantity` 时订单为 `PARTIAL_INBOUND`，全部入库后为 `INBOUND_DONE`。
- 取消与入库确认必须按采购订单串行化，并同时使用订单、入库工作单的乐观锁；任一关联入库工作单已确认、版本已变更或发生确认竞态时，取消整体失败并保持原有状态。
- 当前取消请求只接收版本号；取消原因、责任标记和取消操作人字段仅为数据库预留，尚未接入接口与评分，不从备注推断责任，也不将取消订单计入交付样本。
- 未到承诺日的订单不进入交付样本；到期后读取全部已确认批次，提前到货也计为已交付，迟到批次按确认日期计罚，剩余未交付金额按重算业务日期计罚；草稿、待确认入库单不作为已交付事实。

## 供应商评分规则

所有自动评分使用最近 180 天窗口，计算过程使用 `BigDecimal`，只在最终落库时按 `HALF_UP` 转为 `INT×100`。自动分不接受前端赋值；只有服务分可由人工通过专用接口设置最终值，并且必须填写原因。

- 质量窗口：以业务日期 D（上海时区）计算，`INBOUND_DONE` 订单的 `fully_received_at` 在 `[D-179 00:00, D+1 00:00)`。选中整单后读取它的全部已确认采购入库批次，不再次按批次确认日期截断。
- 供应产品质量分：`Σ(入库合格数量 × 入库单价快照) / Σ((入库合格数量 + 入库不合格数量) × 入库单价快照) * 100`。使用 `inbound_bill_item.unit_price`，不使用采购明细 `total_amount` 或历史累计 `score_basis_amount`；原始乘积使用 `BigInteger`，只在最终评分时舍入。全合格为 100，全不合格为 0，无有效金额为 NULL。
- 供应产品价格分：`min(100, 产品参考采购价 / 有效报价 * 100)`；参考采购价为空、报价无效或过期时为空。产品最低报价只用于候选展示和同分排序，不作为评分公式基准。
- 供应商交付分：选择 `expected_arrival_date` 在 `[D-180, D)` 的 `APPROVED/PARTIAL_INBOUND/INBOUND_DONE` 订单，不以审核日期筛选。采购明细 `total_amount` 仅计一次应交金额；按确认批次数量、未交剩余数量及各自逾期系数计算金额占比，保持数量配平，不把各批次金额提前舍入到整数分。已确认批次按 `confirmed_at` 相对承诺日计罚，尚未确认到货的剩余金额按 D 计罚；完全没有确认入库单的到期订单整单按未交付计罚。公式：`max(0, 100 × (1 - Σ罚额 / Σ到期应交金额))`；按时 0% / 1–3 天 25% / 4–7 天 50% / 8–15 天 75% / 超过 15 天 100%。加权罚额保留亚分精度，最终评分 HALF_UP。承诺日当天确认视为按时，无有效应交金额为 NULL。取消责任样本留待后续明确接入，本期不统计 `CANCELLED`。
- 平均到货周期：对最近 180 天完全入库订单，按 `fully_received_at - approved_at` 计算，并按订单金额加权；它只用于分析，不再重复进入综合分权重。
- 供应商质量分：直接汇总本窗口全部有效产品合格金额、不合格金额后计算同一比值，天然按金额加权，不再平均已舍入的产品分数。供应商价格分按启用、有效价格分且累计 `score_basis_amount > 0` 的供货关系金额加权，报价和参考价调整同步触发价格专用重算。
- 供应产品推荐分：`价格分×30% + 供应商交付分×30% + 产品质量分×30% + 服务分×10%`。
- 供应商综合分：`供应商价格分×30% + 供应商交付分×30% + 供应商质量分×30% + 服务分×10%`。
- 任一必需样本或输入为空时，相关自动分为 `NULL`，状态为 `NOT_READY`；不重新分配权重。

评分事实查询在同一 `REPEATABLE_READ` 事务中分批读取采购单、采购明细、确认入库单、入库明细，另批量校验供货关系归属；每页 200 张采购单，按 ID 游标遍历，不使用固定 `LIMIT 30000` 截断总样本。在 Java 按订单、采购明细归并，异常整单跳过并告警。

本期每日凌晨 02:00（上海时区）直接重算质量、交付、价格、推荐和综合分，人工服务分保持现值。每个供应商独立事务，评分与变化日志原子提交，供应商 Redis 锁提交后释放。开发环境默认关闭定时任务，可通过 `SUPPLIER_SCORE_SCHEDULED_ENABLED=true` 启用。

Redis 保存供应商质量合格/不合格金额两个总额、业务日期与规则版本，以及仅当天已纳入订单的幂等标记，24 小时 TTL，稳定供应商键而非按日期建键；不缓存产品金额 map、180 天全部订单清单或交付事实。凌晨强制查库并原子覆盖总额与今日订单标记，白天核心方法只查当天新完全入库订单的全部批次并追加总额；缓存缺失则全量冷启动，不再追加已包含订单。产品质量明细放在本次 Java 快照中，不进入 Redis。采购单首次完全入库后，在事务提交后登记五分钟合并窗口并发送延迟消息；消费者取得供应商锁、重算评分并写日志，成功提交后按批次原子清理 pending。质量暖缓存的入库增量路径沿用供应商已有交付分，冷启动和凌晨校正重新查询交付事实。

## 测试场景

- 可以新增、编辑、停用供应商。
- 可以维护某供应商能供应哪些产品、报价和交期；自动评分字段只读。
- 创建采购订单时可以选择供应商、仓库和产品明细。
- 采购明细由后端保存供应商推荐分快照；无评分或人工指定供应商时快照为空。
- 审核采购订单后能生成 `PURCHASE_IN` 待确认入库单。
- 确认入库后能更新库存，并回写采购明细已入库数量。
- 新建采购明细时，前端随明细提交 `quantityPrecision` 仅用于显式表达所见产品规则；后端必须重新读取产品精度并校验请求值一致，再把服务端值固化到 `purchase_order_item.quantity_precision`。编辑历史明细、生成入库工作单和采购退货来源均以该明细快照为准，不再联查当前产品精度。
- 确认入库时记录合格数量和不合格数量，并在订单完全入库时写入 `fully_received_at`。
- AI 可以按产品查询 `READY` 候选供应商，并使用推荐分、报价、样本金额和平均到货周期进行选择与解释。

## 扩展点：部门数据范围权限（待后续实现）

> 本节为后续 feature 的扩展点说明，**MVP 阶段不实现**。目的是把"业务表如何承接部门归属"的口子先在文档里定下来，避免将来 feature 上线时改表结构对线上数据造成破坏。

### 采购订单的部门归属规则

MVP 阶段 `purchase_order` / `purchase_order_item` 不携带部门字段，**新增**部门数据范围权限 feature 时，将**在 `purchase_order` 主表新增 `dept_id` 字段**，归属规则采用**创建人归属（快照式）**：

| 字段 | 取值 | 说明 |
| --- | --- | --- |
| `purchase_order.dept_id` | 创建人在创建订单时所属部门的 ID | 取自 `sys_user.dept_id` 在订单创建时刻的快照值 |

**关键约束**：

- **快照式归属**：订单创建瞬间把 `created_by_id` 对应用户的 `sys_user.dept_id` 写入 `purchase_order.dept_id`，**之后不随创建人调岗而变化**。历史采购单的部门归属稳定可追溯。
- **AI 自动生成的采购单**：AI 助手根据建议自动生成采购草稿时，`dept_id` 取**当前登录用户**的部门（不是 AI 助手本身的部门）。`recommend_score` 推荐分字段不参与归属。
- **变更父级不影响历史订单**：若后续需要把订单"迁"到新部门，应提供专门的"调整部门归属"接口（暂不设计），而不是直接覆盖。
- **被删除用户创建的订单**：`deleted = 1` 的用户仍可能在 `created_by_id` 字段上留下历史订单，**不级联清理**，部门快照保留。
- **审核人不参与归属**：审核人（`approved_by_id`）不参与部门归属判定，只记录审批轨迹。

### 扩展后的字段表（计划，仅供参考）

```text
purchase_order（追加字段，未落地）
├── dept_id  bigint NOT NULL DEFAULT 0  -- 部门 ID 快照，来自创建人 sys_user.dept_id
├── KEY idx_purchase_order_dept (dept_id)  -- 配合 ancestors LIKE 过滤
```

**索引选择**：与销售表同样的考虑——`ancestors` LIKE 查询的命中行最终会回表到 `purchase_order.dept_id`，所以 `dept_id` 上必须有索引；`ancestors` 自身在 `sys_dept` 表上**不需要**额外索引（详见销售表扩展点说明）。

### 启用数据范围权限后的查询模式

```sql
-- 1) 算"当前用户 + 所有下级"可见的部门 ID 集合
-- 假设用户 A 的 dept_id = '1900000000000000102'（采购部），数据范围 = 本部门及下级
SELECT id FROM sys_dept
WHERE id = '1900000000000000102'
   OR CONCAT(',', ancestors, ',') LIKE CONCAT('%,', '1900000000000000102', ',%');

-- 2) 把可见部门 ID 集合代入采购订单主查询
SELECT o.*
FROM purchase_order o
WHERE o.dept_id IN ( <步骤 1 的结果集> )
  AND o.deleted = 0
ORDER BY o.create_time DESC
LIMIT ? OFFSET ?;
```

### 暂不设计的相关扩展

- 采购明细 `purchase_order_item` 不单独存 `dept_id`，**直接 join 主表**即可。冗余存储会引入"主表改部门后明细未同步"的一致性维护成本，**禁止**。
- 供应商 `supplier`、`supplier_product` 不参与部门归属（它们是"业务实体"，归属的是"被谁维护/属于哪个组织"维度，**与"采购订单归属"无关**），因此**不**为它们预留 `dept_id` 字段。供应商评分亦不受部门隔离影响——评分是业务指标，按产品和供应商维度计算，不按部门过滤。
- 仓库模块的 `stock_bill` 是否也按创建人归属部门，待与销售、库存模块一起评审后再决定（可能存在"采购入库单与销售出库单分属不同部门"的场景，例如跨部门调拨）。
