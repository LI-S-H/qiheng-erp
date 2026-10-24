# 销售业务口径

> 适用范围：销售订单、锁库、销售出库、销量与销售额统计口径。
> 关联文档：库存三口径与出库单状态见 `warehouse.md`；退货见 `return.md`；数量放大、日期语义、`deleted` 清单见 `shared.md`；客户启停见 `customer.md`。

## 一、速查（先看这几条）

1. **锁库发生在"提交"，不是"审核"**——这是销售模块最高频的坑。
2. `SUBMITTED` 的中文是**待审核**（不是"已提交"）；`APPROVED` 的中文是**待出库**（不是"已审核/已完成"）。
3. **"还剩多少待出库" = `quantity − outbound_qty`**，不是 `locked_qty − outbound_qty`。
4. 出库后明细的 `locked_qty` **不会自动归零**，它记录"曾经锁了多少"；扣的是仓储侧 `warehouse_stock.locked_qty`。
5. **判断"这张单锁过库没有"要看 `locked_at IS NOT NULL`，不能看 `locked_qty`**——历史单没有锁库痕迹。
6. **销售额按 `approved_at` + 三态过滤**（`APPROVED` / `PARTIAL_OUTBOUND` / `OUTBOUND_DONE`）；草稿、待审核、已取消一律不计。
7. **销售订单主表没有"实际出库日"**——出库时间只在 `outbound_bill.confirmed_at` 与 `stock_bill.confirmed_at`。
8. 销售订单**没有删除接口，只能取消**；明细表没有 `deleted`。

## 二、对象与字段

### 2.1 销售订单主表 `sales_order`

| 字段 | 口径 |
|---|---|
| `sales_no` | 单号，前缀 `SO`，唯一 |
| `customer_id` / `customer_code` / `customer_name` | 客户，后两个是快照 |
| `warehouse_id` / `warehouse_name` | 出库仓库 |
| `status` | 见 §3.1 |
| `total_amount` | 订单总金额，**单位分** = 各明细金额之和（不是重新汇总 `quantity × price`） |
| `expected_delivery_date` | 预计发货日期（计划发货日），草稿可空，**提交与审核前必须非空且不早于今天** |
| `submitted_at` | 提交时间（= 锁库时间点） |
| `locked_at` | 库存锁定时间，与 `submitted_at` 同时刻 |
| `approved_at` | 审核时间，**经营金额按此日归属** |
| `create_time` | 建单时间，**不等于下单日** |
| `deleted` | 主表有 |

### 2.2 销售订单明细 `sales_order_item`

| 字段 | 含义 | 什么时候变 |
|---|---|---|
| `quantity` | 销售数量（计划量），×100 | 建单、编辑 |
| `locked_qty` | 已锁定库存数量，×100 | **提交时**置为 `= quantity`；取消时置回 0 |
| `outbound_qty` | 已出库数量，×100 | **仓库确认出库后**累加 |
| `unit_price` / `total_amount` | 单价 / 明细金额，**单位分** | 建单、编辑 |
| `quantity_precision` | 下单时从产品固化的精度快照（0-2 位小数） | 建单 |

**不变量**：`DRAFT` 状态的 `locked_qty` 必须为 0；`SUBMITTED` / `APPROVED` 状态的 `locked_qty` 必须等于 `quantity`。

**编辑会物理删除并重建全部明细**（全删全插），所以 `sales_order_item.id` 在编辑后会变；外部引用明细 id 的记录（出库单的 `source_item_id`）在编辑后失效。

**同一订单不允许重复产品**，聚合时不要按产品重复计数。

### 2.3 销售出库单

审核时同事务生成一张 `outbound_bill`：`outbound_type = SALES_OUT`、`source_type = SALES_ORDER`、`entry_mode = SOURCE_GENERATED`、`status = PENDING_CONFIRM`。

明细的三个数量：`plan_qty = quantity`、`processed_qty = outbound_qty`、`current_qty = 0`（**本次出库数量初始为 0，由仓库按实物填写**）、`pending_qty = quantity − outbound_qty`。

## 三、状态机与流转

### 3.1 销售订单状态

| 状态 | 中文名 | 含义 | 能否编辑 | 能否取消 |
|---|---|---|---|---|
| `DRAFT` | 草稿 | 不锁库、不生成出库单 | 可以 | 可以 |
| `SUBMITTED` | **待审核** | **已锁库**（`locked_qty` 已占用） | 可以（不能改仓库） | 可以 |
| `APPROVED` | **待出库** | 审核通过，已生成待确认出库单 | 不可以 | 可以（需先取消未确认出库单） |
| `PARTIAL_OUTBOUND` | 部分出库 | 部分明细已确认出库 | 不可以 | **不可以** |
| `OUTBOUND_DONE` | 已出库 | 全部明细已出库 | 不可以 | 不可以 |
| `CANCELLED` | 已取消 | 终态 | 不可以 | — |

**同一状态在不同页面还有第三、第四套中文名**（工作台把 `APPROVED` 叫"已审核"、销售退货页叫"待退货入库"）。报告里统一用上表这套。

### 3.2 流转与取消释放

```
DRAFT --提交(submit)--> SUBMITTED      ← 这一步锁库
SUBMITTED --审核(approve)--> APPROVED  ← 这一步不锁库，只做兜底校验 + 生成出库单
```

**提交时**（同一事务内）：行锁 → 校验可用库存 → `warehouse_stock.locked_qty += quantity` → 明细 `locked_qty = quantity` → 写 `locked_at` → 状态置 `SUBMITTED`。

**怎么校验可用库存**：

```
available = warehouse_stock.stock_qty − warehouse_stock.locked_qty
要求 available >= quantity
```

该仓库下**没有库存记录**直接失败（"产品 X 在该仓库无库存记录"）。**锁多少**：逐明细锁 `quantity`（不是差额、不是 0）。

**审核时**只做锁定结果兜底校验（`locked_qty == quantity`，不等就报"数据异常"）并生成出库单，**不扣库存、不写流水**。

**取消时怎么释放**：

- `DRAFT` 取消：不释放（没有锁定）。
- `SUBMITTED` 取消：释放全额锁定量。
- `APPROVED` 取消：**先**加分布式锁防与仓库确认出库死锁 → 校验关联出库单**未 `CONFIRMED`** → 把这些出库单置 `CANCELLED` → 再释放锁定量。**已有确认出库则拒绝取消。**
- **释放顺序是"先拿取消权再释放库存"**，不是反过来。

**`SUBMITTED` 状态编辑**按差额精确回算锁定量（先释放再预占）；**该状态禁止改仓库**。

### 3.3 出库确认与状态推进

**不允许超发**，三层防护：

1. 仓库侧：`current_qty <= plan_qty − processed_qty`。
2. 销售侧回写：累加后校验 `next_outbound <= plan_qty`。
3. 本次数量必须 > 0。

**允许分批**：仓库确认一张出库单后若未全部出库，系统自动再生成一张只含剩余数量的待确认出库单。

**出库确认对库存的影响**：一次性**同时扣减** `stock_qty` 和 `locked_qty`（各减 `current_qty`）。前置校验 `stock_qty >= current_qty` 且 `locked_qty >= current_qty`——**只消费已预占的锁定量，不会重新抢占可用库存**，锁定量不足整体回滚。

**状态推进**：全部明细 `outbound_qty >= quantity` → `OUTBOUND_DONE`；至少一条 `outbound_qty > 0` → `PARTIAL_OUTBOUND`；`OUTBOUND_DONE` 不再回退。

### 3.4 建单与审核的校验

**建单时**：客户必填/存在/**启用**；仓库必填/存在/**启用**；产品每条明细必填/存在/**启用**；明细至少 1 条；数量 > 0；单价 >= 0；同一订单内不允许重复产品；数量小数位不超过产品精度。

**提交/审核时重校验**（草稿期间基础资料可能已变）：客户/仓库/产品启用状态重新校验；预计发货日期非空且不早于今天；审核时再校验锁定量与数量一致。

**客户停用后不能下单**（既有订单的退货不受此限制）。

**⚠️ 一个实现缺口**：代码在**采购**建单路径没有校验产品 `status`（文档写了但没实现），销售侧是有的。做跨模块结论时要留意这个差别。

## 四、关键口径

### 4.1 数量与金额

| 字段 | 存储 |
|---|---|
| `quantity` / `locked_qty` / `outbound_qty` | `BIGINT`，**×100** |
| `unit_price` / `total_amount` | `BIGINT`，**单位分（×100）** |

```
明细金额（分）= quantity × unit_price        （HALF_UP 保留 2 位）
订单总金额（分）= 各明细金额之和              （不是重新汇总 quantity×price）
```

**系统里没有折扣、没有税、没有运费字段**，不要编。数量与金额都是 ×100 整数，靠命名区分语义：`unit_price`/`total_amount` 是"分"，`quantity`/`locked_qty`/`outbound_qty` 是"数量×100"。

### 4.2 时间归期

| 要统计的事 | 用哪个时间 | 不能用哪个 |
|---|---|---|
| 销售额（经营口径） | `approved_at` | `create_time`、`submitted_at`、出库日 |
| 销量 / 出库量 | `outbound_bill.confirmed_at`（或流水 `confirmed_at`） | 订单 `create_time` |
| 下单日 | `submitted_at` | `create_time` |
| 计划发货 | `expected_delivery_date` | 不能当实际发货日 |

### 4.3 ★ 历史订单没有锁库痕迹

锁库是后加的功能：2026-06 ~ 2026-08 的销售订单里大量 `locked_at` 为空、明细 `locked_qty = 0`，但 `outbound_qty = quantity`（货确实出库了）。新单（2026-08 中旬之后建的）才是 `locked_at = submitted_at`、明细 `locked_qty = quantity`。

所以：**判断"这张单锁过库没有"看 `locked_at IS NOT NULL`，不能看 `locked_qty`**——历史单的 `locked_qty = 0` 是"功能上线前没有这个动作"，不是"锁了 0"。拿 `locked_qty` 做"锁库率"之类的分析会得出完全错误的结论。

## 五、业务定义

### 5.1 销售额（经营口径）

```sql
WHERE so.status IN ('APPROVED','PARTIAL_OUTBOUND','OUTBOUND_DONE')
  AND so.approved_at >= ? AND so.approved_at < ?
```

用 **`approved_at`（审核日）归属日期**，不用 `create_time`、`submitted_at`，也不用出库日。草稿、待审核、已取消一律不计。金额取 `sales_order.total_amount`（单位分）。

**⚠️ 工作台"订单流转阶段分布"用的是 `create_time` 落在本月 + 按状态分组**，与经营金额口径（`approved_at` + 三态）**完全是两套**。同一个模块两套时间字段，别混用。

### 5.2 销量

销量 = 已确认销售出库数量，粒度可以是 `(仓库, 产品)` 或产品；时间按 `outbound_bill.confirmed_at` 归期，只统计 `CONFIRMED` 且 `outbound_type = SALES_OUT` 的单据（见 `warehouse.md`）。

**销量不是销售订单量**：订单量按 `approved_at` 统计已审核的下单数量，销量按出库确认统计实际出库数量，两者会因未出库、分批出库、欠出而不同。用户问"销量"时默认按出库口径，并在 `instruction` 里写明。

### 5.3 待出库量

```
待出库量 = quantity − outbound_qty
```

不是 `locked_qty − outbound_qty`。判断"这个货还够不够卖"时，占用口径是**已提交未出库**（`SUBMITTED` / `APPROVED` / `PARTIAL_OUTBOUND` 的剩余量），已锁部分已体现在 `warehouse_stock.locked_qty` 里，**不要重复扣**（见 `warehouse.md` §4.2）。

### 5.4 发货承诺与逾期

系统只有 `expected_delivery_date`（计划发货日），**没有实际发货日**，实际发货事实用 `outbound_bill.confirmed_at`。判"发货是否按时"只能按"已确认出库时间 vs 计划发货日"，且未出库的订单不能判延期——与采购侧延期清单口径同理（见 `purchase.md` §5）。

## 六、常见错误

1. 以为审核销售单才锁库 → **提交时就锁库**。
2. 用 `locked_qty − outbound_qty` 算待出库 → 应为 `quantity − outbound_qty`。
3. 以为出库确认后明细 `locked_qty` 归零 → 它保留"曾经锁了多少"。
4. 用 `locked_qty = 0` 判断没锁过库 → 历史单本来就没有锁库痕迹，要看 `locked_at`。
5. 销售额用 `create_time` 或出库日归期 → 经营口径是 `approved_at`。
6. 把工作台的 `create_time` 阶段分布当销售额 → 两套口径，不同时间字段。
7. 把已审核订单量当销量 → 销量按出库确认时间与实际出库量。
8. 编造折扣、税、运费 → 系统没有这些字段。
9. 用 `update_time` 当完成时间 → 完成事实看 `confirmed_at` / `approved_at`。
10. 在 `sales_order_item` 上加 `deleted = 0` → 明细表没有这一列。
11. 按产品重复计数 → 同一订单不允许重复产品，但多张单要按业务口径去重。
12. 拿当前主数据覆盖历史单据的精度/单价快照 → 快照是创建时副本。

## 七、写 `instruction` 时的提醒

1. 算可用库存 / 可承诺量用"账面 − 锁定"（见 `warehouse.md`）。
2. 算"还能出多少"写成"销售数量 − 已出库数量"，不要写成"锁定数量 − 已出库数量"。
3. 算销售额要写明"按审核时间归期 + 三个有效状态过滤"；算销量要写明"按出库确认时间归期 + 已确认出库数量"，并给出窗口与粒度。
4. 判断占用/锁定用"已提交未出库"口径，且不要从可用库存重复扣已锁需求。
5. 过滤状态要写枚举字面量（`CONFIRMED`、`CANCELLED`），中文名只是给人看的；列名不写进 `instruction`。
6. 数字必须标单位（金额「分」或已换算的「元」、数量业务单位），同一需求内保持一致。
7. 要"某月实际出库"时写明"按出库确认时间归月"，不要用建单时间；跨月建单会偏移。