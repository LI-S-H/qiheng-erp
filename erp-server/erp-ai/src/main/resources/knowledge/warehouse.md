# 仓储业务口径

> 适用范围：仓库档案、库存余额、出入库作业单、库存流水、库存健康与滞销周转。
> 关联文档：数量放大与删除/日期通用口径见 `shared.md`；在途采购量与净缺口见 `purchase.md`；销售锁库与销售额见 `sales.md`；安全库存字段与数量精度见 `product.md`。

## 一、速查（先看这几条）

1. `warehouse_stock` 只有两个数量字段：账面 `stock_qty`、锁定 `locked_qty`；**可用 = 账面 − 锁定**，这是唯一的"可用"口径，表里没有 `available_qty` 这一列。
2. **只有 `CONFIRMED` 的出入库单才改库存**；草稿、待确认单的库存实际变动为 0。
3. **销售锁库发生在"提交"，不是"审核"**；审核只校验锁定量。
4. 在途量不在仓储表里，要去采购侧算（见 `purchase.md`）；**在途量与 `locked_qty` 是两套数字，不能相加或混用**——采购入库从不写 `locked_qty`。
5. 判断低库存 / 风险用**可用库存**比安全库存；判断滞销和周转用**账面库存**（货还在仓库，锁定只是预占）。
6. 每张分批作业单都**重复保存 `plan_qty`**（= 来源明细全量），累加会严重重复计数。
7. 库存流水 `stock_bill` **没有 `status`、没有 `deleted`**；**出库明细没有单价列**，只有入库明细有 `unit_price`。
8. 跨单位不能汇总数量；数量是 ×100 存储（见 `shared.md`）。

## 二、对象与字段

### 2.1 库存余额 `warehouse_stock`

| 字段 | 说明 |
|---|---|
| `warehouse_id` / `warehouse_code` / `warehouse_name` | 仓库。后两个是**冗余当前态**，不是外键 |
| `product_id` / `product_code` / `product_name` / `unit_name` | 产品。后三个是**冗余快照** |
| `stock_qty` | 当前账面库存数量，×100 |
| `locked_qty` | 锁定库存数量，×100 |
| 无 `deleted` | 事实余额表，不软删 |

**唯一粒度是 `(warehouse_id, product_id)`**——没有库位、没有批次、没有序列号。一个仓库一个产品只有一行余额。

### 2.2 出入库作业单 `inbound_bill` / `outbound_bill`

数量三件套：`plan_qty`（来源明细计划总量）、`processed_qty`（生成本单前已确认的累计量）、`current_qty`（本次数量）。
恒等式：`processed_qty + current_qty + pending_qty = plan_qty`。

- `expected_arrival_date`（预计到货日）**只在 `inbound_bill` 主表**，且**只有系统生成的采购入库单有值**；库存流水不复制这个字段。
- 质检字段：`qualified_qty` / `defective_qty` 只是质检记录，**不参与库存计算**。`PURCHASE_IN`、`SALES_RETURN`、`PURCHASE_RETURN` 要求两者之和 = `current_qty`；`ADJUST_IN`、`ADJUST_OUT`、`SALES_OUT` 必须为 0。

### 2.3 库存流水 `stock_bill`

| 字段 | 说明 |
|---|---|
| `bill_type` | `PURCHASE_IN`、`SALES_OUT`、`PURCHASE_RETURN`、`SALES_RETURN`、`ADJUST_IN`、`ADJUST_OUT` |
| `change_qty` | 入库为正、出库为负，绝对值 = 本次数量 |
| `before_qty` / `after_qty` | 变动前后库存，用于追溯 |
| `confirmed_at` | 确认时间，**库存与出入库事实的归期时间** |
| `entry_mode` | 流水上的快照：`SOURCE_GENERATED` / `MANUAL_SUPPLEMENT` / `MANUAL_ADJUSTMENT` |

- **没有 `status`**：一生成就代表"已确认的库存事实"，不可改不可取消。
- 来源类型由 `bill_type` **推导**（表里没有 `business_source_type` 列）：`PURCHASE_IN→PURCHASE_ORDER`、`SALES_OUT→SALES_ORDER`、`PURCHASE_RETURN→PURCHASE_RETURN_ORDER`、`SALES_RETURN→SALES_RETURN_ORDER`、`ADJUST_IN`/`ADJUST_OUT`→`STOCK_ADJUST`。

### 2.4 仓库档案 `warehouse`

- `status`：`1` 启用 / `0` 禁用（`TINYINT`，**不是英文枚举**）。
- 停用前必须：库存清零、无草稿/待确认出入库单、无其他模块的未完成引用。
- 删除前必须：**完全没有库存记录**（库存为 0 也不许删）、无历史单据或流水、无业务引用。
- 仓库改名时**只同步 `warehouse_stock.warehouse_name`**；已生成的单据和流水保留创建时快照。

### 2.5 与其他模块的关系

**所有表引用仓库都用 `warehouse_id`（bigint）**，没有一张表用 `warehouse_code` 做外键；`warehouse_code` 只出现在仓库表自身和库存余额表的冗余展示字段里。

| 表 | 引用字段 | 语义 |
|---|---|---|
| `purchase_order` | `warehouse_id` | 目标入库仓库 |
| `sales_order` | `warehouse_id` | 出库仓库 |
| `return_order` | `warehouse_id` | 退货执行仓库 |
| `inbound_bill` / `outbound_bill` / `stock_bill` | `warehouse_id` | 对应仓库 |

**产品模块不引用仓库**，关系方向是 `warehouse_stock.product_id → product.id`；产品与仓储唯一的耦合是安全库存 `product.safety_stock_qty`。

## 三、状态机与流转

### 3.1 出入库作业单状态

`inbound_bill` 与 `outbound_bill` 共用一套状态：

| 状态 | 含义 | 能不能改 | 能不能取消 |
|---|---|---|---|
| `DRAFT` | 草稿（只有手工补录/库存调整从 DRAFT 开始） | 全字段可改 | 可以 |
| `PENDING_CONFIRM` | 待确认（**来源单生成的直接落这一状态**） | 只改本次数量、质检数量、备注；**不能增删明细** | 仅手工单可以 |
| `CONFIRMED` | 已确认，**终态** | 不可改 | 不可取消 |
| `CANCELLED` | 已取消，**终态** | 不可改 | — |

**关键规则**：

- **系统生成的作业单（`entry_mode = SOURCE_GENERATED`）不能通过仓储侧取消**，必须通过来源单取消，否则会留下"孤儿锁定"。只有 `MANUAL_SUPPLEMENT` / `MANUAL_ADJUSTMENT` 能走仓储侧取消。
- **`CONFIRMED` 之后不允许任何修改或取消**，发现错误只能通过反向单据或库存调整纠正。
- 确认时的数量上限：`current_qty <= plan_qty - processed_qty`，**不允许超收，只允许欠收**。欠收时来源单进入 `PARTIAL_*`，并自动生成下一张只含剩余量的待确认单。

### 3.2 库存改动的六个来源

**库存改动的唯一入口是仓储模块。** 采购、销售、退货都**不直接改库存**，只生成待确认作业单，仓库确认后才落流水并改余额。

| 业务场景 | 作业单 | 类型枚举 | 来源类型 | 确认后对 `warehouse_stock` 的影响 |
|---|---|---|---|---|
| 采购入库 | `inbound_bill` | `PURCHASE_IN` | `PURCHASE_ORDER` | `stock_qty += current_qty` |
| 销售退货入库 | `inbound_bill` | `SALES_RETURN` | `SALES_RETURN_ORDER` | `stock_qty += current_qty`（不预占） |
| 库存调整入库（盘盈） | `inbound_bill` | `ADJUST_IN` | `STOCK_ADJUST` | `stock_qty += current_qty` |
| 销售出库 | `outbound_bill` | `SALES_OUT` | `SALES_ORDER` | `stock_qty -= current_qty` **且** `locked_qty -= current_qty` |
| 采购退货出库 | `outbound_bill` | `PURCHASE_RETURN` | `PURCHASE_RETURN_ORDER` | 同上，同时扣两个字段 |
| 库存调整出库（盘亏） | `outbound_bill` | `ADJUST_OUT` | `STOCK_ADJUST` | 同上 |

**入库确认只加 `stock_qty`，绝不动 `locked_qty`；出库确认同时扣两个字段。**

**没有调拨单，也没有盘点单。** 跨仓移动与盘盈盘亏暂时只能用 `ADJUST_IN` / `ADJUST_OUT` 手工库存调整表达（`entry_mode = MANUAL_ADJUSTMENT`，原因必填）。

## 四、关键口径

### 4.1 库存的三个口径（最关键）

| 名称 | 字段 | 口径 | 什么时候变 |
|---|---|---|---|
| **当前库存 / 账面库存** | `stock_qty` | 仓库账上现在有多少实物 | 只有确认入库 / 确认出库才变 |
| **锁定库存 / 已占用** | `locked_qty` | 已被下游单据预占、但还没真正出库的量 | 出库链路加，出库确认时扣 |
| **可用库存** | `stock_qty − locked_qty` | 唯一的"可用"口径，服务层派生，不落库 | 随上面两个变 |

### 4.2 锁定库存的生命周期

| 触发动作 | `locked_qty` | `stock_qty` |
|---|---|---|
| **销售订单提交**（DRAFT→SUBMITTED） | **+ 全部销售数量** | 不变 |
| 销售订单审核 | 不变（只校验锁定量 == 数量） | 不变 |
| **采购退货单审核通过** | **+ Σ(已审核量 − 已处理量)** | 不变 |
| 销售退货单审核通过 | 不变（入库方向不预占） | 不变 |
| 手工调整出库单建单（DRAFT） | **+ 本次数量** | 不变 |
| **出库单确认** | **− 本次数量** | **− 本次数量** |
| **入库单确认** | 不变 | **+ 本次数量** |
| 销售订单取消 | **− 全额锁定量** | 不变 |
| 采购退货单取消 | **− Σ(已审核 − 已处理)** | 不变 |
| 手工调整出库单取消 / 编辑 | 按差额释放 | 不变 |

**销售方向没有"在途"概念**——销售出库在提交订单时就锁了库，所以"在途"只用于采购补货场景（见 `purchase.md`）。

### 4.3 时间归期

| 要统计的事 | 用哪个时间 | 不能用哪个 |
|---|---|---|
| 出入库事实、销量、出库量 | `stock_bill.confirmed_at` / 作业单 `confirmed_at` | 单据 `create_time`、`update_time` |
| 库存余额当前状态 | 本次快照时刻 | 历史余额（表里不存历史快照） |
| 预计到货 | `inbound_bill.expected_arrival_date` | 不能当实际到货日 |

需要"某时点的库存"时，只能用 `stock_bill` 的 `before_qty` / `after_qty` 回溯重建，不能拿当前余额倒推历史每日库存。

### 4.4 数量与单位

- 库存、锁定、出库数量都是 ×100 整数；报告展示前还原成业务数量（见 `shared.md`）。
- 同一产品跨仓可以汇总；**跨单位（件 / 箱 / kg）不能汇总数量**。
- 数量步长受产品 `quantity_precision` 约束（见 `product.md`）。

## 五、业务定义

### 5.1 库存健康（查询时派生，不落库）

| 状态 | 判定 |
|---|---|
| `OUT_OF_STOCK` | `stock_qty = 0`（无库存） |
| `NO_AVAILABLE` | `stock_qty > 0` 且可用 ≤ 0（全被锁定） |
| `LOW_STOCK` | 0 < 可用 ≤ 安全库存 |
| `NORMAL` | 可用 > 安全库存 |

安全库存来自 `product.safety_stock_qty`（×100，产品级、不区分仓库），比较时用可用库存，两者同为 ×100 口径可直接比。**为什么"用可用比账面口径的阈值"、报告要怎么同时给两个数，见 `product.md` §5.1**。

### 5.2 风险缺口（现状口径）

```
风险缺口 = max(0, 安全库存 − 可用库存)
```

- 回答"**现在离安全线差多少**"，只用当前快照，不含需求预测、不含在途。
- 它**不是净缺口**：净缺口 = 需求 − 可用 − 在途，回答未来够不够，见 `purchase.md`。
- 它也不是建议补货量：工作台的补货建议是 `max(0, 安全库存 × 2 − 可用库存)`，两者不要混用同一个名字。

### 5.3 滞销

粒度 `(仓库, 产品)`，同时满足以下条件即判为滞销：

1. 近 90 天该仓库该产品的**销售出库量为 0**——只统计已确认（`CONFIRMED`）的 `SALES_OUT`，按 `confirmed_at` 归期；
2. 当前账面库存 `stock_qty > 0`；
3. 仓库启用未删除、产品启用未删除。

- **锁定不影响滞销判定**：锁定只是预占，货还在仓库里，实物仍在占用资金。
- 90 天是默认窗口；用户指定别的窗口时以用户为准，并在 `instruction` 里写明窗口。
- 系统里没有"滞销标记"字段，这个状态是查询时派生的，不能当成表里的列去查。

### 5.4 库存周转天数（运营近似口径）

含义：按现在的卖货速度，仓库里的货还要多少天卖完。数值越小周转越快。

```
周转天数 = 当前账面库存 ÷（近 90 天销售出库量 ÷ 90）
```

- 粒度按仓库汇总；要看单个产品就按 `(仓库, 产品)` 同式计算。
- 分子用**账面库存 `stock_qty`**（实物都算占用），不用可用库存。
- **只用数量，不用金额**：出库明细没有成本单价；用参考采购价会在分子分母同时出现、互相抵消。
- 近 90 天销售出库量为 0 时**返回空并标注"无近期销售"**，不要返回 0，也不要算成无穷大。
- 这是**运营近似**，不是财务口径：系统没有历史库存快照，分子用当前库存代替"平均库存"。要财务口径必须先用品流重建期初期末平均，另说明。
- 用户说"**周转率**"时不必另造指标，也不必将就着问：按本式算周转天数即可（两者互为倒数，周转率低 = 周转天数高 = 卖得慢），但结论里要写明"按周转天数口径"，不要两个名字混着报。

## 六、常见错误

1. 把 `stock_qty` 当成"能卖出去的量" → 会超卖。判断够不够一律用 `stock_qty − locked_qty`。
2. 把 `locked_qty` 当成"已出库" → 锁定期内 `stock_qty` 不动，货还在仓库里。
3. 以为"审核销售订单就扣库存" → 审核不扣 `stock_qty`，只生成待确认出库单。
4. 以为"草稿/待确认单已经改了库存" → 只有 `CONFIRMED` 才产生库存流水与余额变动。
5. 出库确认时用可用库存兜底 → 代码要求 `locked_qty >= 本次出库量`，不足直接失败，不能绕过预占。
6. 把在途量和 `locked_qty` 相加，或拿采购入库去改锁定 → 采购入库从不写 `locked_qty`。
7. 累加多张分批单的 `plan_qty` 算应交量 / 在途 → 严重重复计数。
8. 用 `update_time` 当库存变动时间，或用 `create_time` 当出入库时间 → 归期用 `confirmed_at`。
9. 用当前余额倒推历史每日库存，或用历史次品累计量猜当前不可售余额 → 余额没有历史快照、也没有质量分桶。
10. 给滞销、周转天数编造"表里有的字段" → 这两个都是查询时派生的口径。
11. 库存流水表加 `deleted = 0` 过滤 → `stock_bill` 没有该列，SQL 会报错。

## 七、写 `instruction` 时的提醒

1. 算可用库存要写成"可用 = 账面 − 锁定"，不能只写"库存"。
2. 算低库存 / 风险要用可用库存比安全库存，并说明安全库存是产品级阈值（不区分仓库）。
3. 算在途量去采购侧（见 `purchase.md`），不要在仓储表里找在途字段。
4. 统计库存事实要排除草稿（`DRAFT`）与已取消（`CANCELLED`）的作业单——它们没有产生实际库存变动；状态枚举要写全，表名字段名不写。
5. 数字必须标单位（件 / 箱 / kg），跨单位不汇总。
6. 写时间窗口要写绝对区间和归期字段（按出库确认时间），不要只写"最近"。
7. 判滞销、算周转天数按本文 §5 的定义写口径，不要现场发明窗口或阈值；用户给的窗口优先，并在 `instruction` 里写明。
8. 需要"某时点库存"时，明确说明用品流回溯重建，不接受用当前余额顶替。