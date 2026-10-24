# 退货业务口径

> 适用范围：统一退货单——销售退货（`SALES_RETURN`）与采购退货（`PURCHASE_RETURN`）。两者共用 `return_order` + `return_order_item`，但可退基数、锁库行为、库存方向**完全不同**。
> 关联文档：出入库作业单与库存三口径见 `warehouse.md`；采购来源单见 `purchase.md`；销售来源单见 `sales.md`；数量放大、`deleted` 清单见 `shared.md`。

## 一、速查（先看这几条）

1. **可退基数是"实际已出 / 已入库量"，不是下单量**：没出库的销售单不能退，没入库的采购单不能退。
2. **销售退货 = 入库方向，审核不锁库；采购退货 = 出库方向，审核会实物预占**。两套"占用"含义完全不同，不能混。
3. **采购退货的可退上限还要减可用库存**（`stock_qty − locked_qty`），销售退货不看库存。
4. **`return_order.total_amount` 是"申请金额快照"**，既不是应退款也不是实际执行金额；要算已审批退货金额必须 `approved_qty × unit_price` **现场算**。
5. **没有"审核不通过"状态**——不通过只能走取消（`SUBMITTED → CANCELLED`，原因写 `status_reason`）。
6. 只有 `DRAFT` 能删除（物理删除明细）；`APPROVED` 只能查看和取消。
7. 退货单**没有"执行日期"字段**，实际入库 / 出库时间在 `inbound_bill.confirmed_at` 与 `stock_bill.confirmed_at`。
8. 退货单**不改变来源单状态**——销售订单没有"已退货"态。
9. 明细表 `return_order_item` **没有 `deleted`**。

## 二、对象与字段

### 2.1 退货单主表 `return_order`

| 字段 | 口径 |
|---|---|
| `return_no` | 单号，现行前缀 `SR`（销售退货）/ `PR`（采购退货）；**2026-06 的历史号段是 `SRO` / `PRO`**，判断方向要用 `return_type`，不要按前缀（见 `shared.md`） |
| `return_type` | `SALES_RETURN` / `PURCHASE_RETURN`，创建后不可改 |
| `source_order_id` / `source_order_no` | 来源销售订单或采购订单 |
| `party_id` / `party_code` / `party_name` | 销售退货为客户、采购退货为供应商，**由原订单带出快照，不能在退货单中单独更换** |
| `warehouse_id` / `warehouse_name` | 退货执行仓库，**必须与原订单仓库一致** |
| `expected_execution_date` | 预计退货执行日期（预计入库 / 出库日），草稿可空，**提交前必填且不得早于当天** |
| `handling_type` | `REFUND` / `EXCHANGE` / `OTHER`；**后端只校验非空，没有强制枚举值**——不要依赖它做业务判断 |
| `reason_code` / `return_reason` | 退货原因（枚举 + 补充说明），提交前必填 |
| `status` / `status_reason` | 状态与最近一次审核退回或取消原因 |
| `total_amount` | **当前有效退货总金额 = Σ(`requested_qty × unit_price`)，是申请快照**，单位分 |
| `submitted_at` / `approved_at` | 提交时间 / 审核时间（经营金额按 `approved_at` 归期） |
| `deleted` | 主表有 |

### 2.2 退货明细 `return_order_item`

| 字段 | 含义 |
|---|---|
| `source_order_item_id` | 来源订单明细 |
| `source_fulfilled_qty` | 创建退货明细时原订单累计已出库 / 已入库数量快照（×100） |
| `requested_qty` | 申请退货数量（×100） |
| `approved_qty` | 审核通过数量（×100） |
| `processed_qty` | **仓库累计确认的实际处理总量**（×100） |
| `unit_price` / `total_amount` | 来源明细单价快照与当前有效明细金额，单位分 |
| `quantity_precision` | 来源明细的精度快照 |
| 无 `deleted` | 明细表没有软删列 |

合格 / 不合格数量的拆分保存在 `inbound_bill_item` 与 `stock_bill_item`，**不回写退货明细**。

### 2.3 两个方向的差别（一张表，两套逻辑）

| | 销售退货 `SALES_RETURN` | 采购退货 `PURCHASE_RETURN` |
|---|---|---|
| **来源单** | 销售订单（`PARTIAL_OUTBOUND` / `OUTBOUND_DONE`） | 采购订单（`PARTIAL_INBOUND` / `INBOUND_DONE`） |
| **库存方向** | **入库**（货回到仓库） | **出库**（货退回供应商） |
| **可退基数** | `sales_order_item.outbound_qty`（实际已出库量） | `purchase_order_item.inbound_qty`（实际已入库量） |
| **还受库存限制吗** | **不受** | **受**：可退上限还要减可用库存 |
| **审核后锁库吗** | **不锁** | **锁** `locked_qty += (已审核量 − 已处理量)` |
| **确认时库存** | `stock_qty += current_qty` | `stock_qty -= current_qty` **且** `locked_qty -= current_qty` |
| **往来单位** | 客户（由原订单带出，不能单独更换） | 供应商 |

## 三、状态机与流转

### 3.1 状态

| 状态 | 中文名（销售方向） | 中文名（采购方向） | 含义 |
|---|---|---|---|
| `DRAFT` | 草稿 | 草稿 | 可编辑、可删除 |
| `SUBMITTED` | 待审核 | 待审核 | 已提交，等审核 |
| `APPROVED` | **待退货入库** | **待退货出库** | 审核通过，已生成待确认作业单 |
| `PARTIAL_EXECUTED` | 退货入库中 | 退货出库中 | 部分明细已处理 |
| `COMPLETED` | 已完成 | 已完成 | 全部明细处理完 |
| `CANCELLED` | 已取消 | 已取消 | 终态 |

**可编辑范围**：`DRAFT` 与 `SUBMITTED` 可编辑（`SUBMITTED` 编辑会按来源订单分布式锁重新聚合可退量）；`APPROVED` 只能查看和取消；`PARTIAL_EXECUTED` / `COMPLETED` 只能查看。

### 3.2 审核与执行

**审核时校验**：`0 <= approved_qty <= requested_qty`，符合数量精度；**至少一条明细审核量 > 0**（全部不通过必须走取消）。

**审核通过**（同一事务内）：

- 生成待确认作业单（销售退货 = 入库单 `SALES_RETURN`；采购退货 = 出库单 `PURCHASE_RETURN`）。
- **采购退货同时预占库存** `locked_qty += (approved_qty − processed_qty)`；销售退货**不预占**。

**确认时**：本次数量 `current_qty <= plan_qty − processed_qty`（剩余待处理量），**不允许超处理**。处理后累加 `processed_qty`；未处理完则**自动生成下一张只含剩余量的待确认单**（**不重复预占**）。

**退货进度**：`processed_qty` 累计的是仓库实际处理总量（本次 `current_qty` = 合格 + 不合格），不是"合格数量"。

**取消已审核的采购退货单**：同事务取消待确认出库单并释放全部 `approved_qty − processed_qty` 的锁定量。

### 3.3 来源单筛选

- **订单层面**：只有已产生实际出入库事实的订单才能作为退货来源——销售退货只认 `PARTIAL_OUTBOUND` / `OUTBOUND_DONE`，采购退货只认 `PARTIAL_INBOUND` / `INBOUND_DONE`。
- **明细层面**：只返回 `outbound_qty > 0`（销售）或 `inbound_qty > 0`（采购）的行。
- **仓库一致性**：退货仓库必须与原订单仓库一致，否则报"退货仓库必须与原订单仓库一致"。

## 四、关键口径

### 4.1 可退数量上限

**销售退货**：

```
可退数量 = max(0, sales_order_item.outbound_qty − 该明细在其他有效退货单中的占用数量)
```

销售退货的"库存可用量"恒为 0（入库操作不查库存），但**这不代表不可退**——看的是上面的可退数量。采购退货的"库存可用量"为 0 才是真的不可退。

**采购退货**：

```
可退数量 = max(0, min(
    purchase_order_item.inbound_qty − 该明细在其他有效退货单中的占用数量,
    warehouse_stock.stock_qty − warehouse_stock.locked_qty
))
```

### 4.2 占用数量（防重复退货的关键）

**按退货单状态取值**：

| 退货单状态 | 占用量 |
|---|---|
| `DRAFT` | 0 |
| `CANCELLED` | 0 |
| `SUBMITTED` | `requested_qty`（申请量） |
| `APPROVED` / `PARTIAL_EXECUTED` / `COMPLETED` | `approved_qty`（审核量） |

**两套占用，别混**：

1. **可退额度占用**（`return_order_item` 层面，按申请量 / 审核量）——防同一批货被重复退。
2. **实物库存预占**（`warehouse_stock.locked_qty`）——防库存被其他出库吃掉。**只有采购退货有。**

### 4.3 ★ 退货金额是「申请金额快照」

```
return_order.total_amount = Σ( requested_qty × unit_price )
```

单价取来源单明细的快照。**审核只改 `approved_qty`，仓储确认只改 `processed_qty`，两者都不重算金额；取消后仍保留该历史金额。**

**这个金额既不是"应退款"也不是"实际执行金额"**：要统计"已审批退货金额"必须用 `approved_qty × unit_price` 现场算，不能读 `total_amount`。

### 4.4 时间归期

| 要统计的事 | 用哪个时间 |
|---|---|
| 退货经营金额 | `approved_at`（审核日） |
| 实际退货入库 / 出库 | `inbound_bill.confirmed_at` / `outbound_bill.confirmed_at`、流水 `confirmed_at` |
| 预计执行 | `expected_execution_date`，**不能当实际执行日** |

**⚠️ `expected_execution_date`（预计退货执行日）与 `expected_delivery_date`（预计发货日）语义不同**，别混。

### 4.5 退货原因

`reason_code`（提交前必填）：`QUALITY_ISSUE`（质量问题）、`DAMAGED`（破损）、`WRONG_ITEM`（错货）、`QUANTITY_ERROR`（数量错误）、`SPEC_MISMATCH`（规格不符）、`NO_LONGER_NEEDED`（不再需要）、`OTHER`（其它）。历史数据中可能残留旧编码，统计时按规范口径合并。

## 五、业务定义

### 5.1 退货经营金额

```
WHERE status IN ('APPROVED','PARTIAL_EXECUTED','COMPLETED')
  AND approved_at >= ? AND approved_at < ?
  金额 = Σ(approved_qty × unit_price)      ← 现算，不读 total_amount
```

### 5.2 可退额度与保护判断

- **可退占用聚合**：`status NOT IN ('DRAFT','CANCELLED')`。
- **仓库停用阻挡判断**（退货）：`status NOT IN ('COMPLETED','CANCELLED')`。
- **客户停用阻挡判断**（退货）：`status NOT IN ('COMPLETED','CANCELLED')`。
- 采购退货未完成也会**阻挡供应商停用**（见 `supplier.md`）。

### 5.3 退货不改变来源单

退货单不修改来源销售 / 采购订单的状态：销售订单没有"已退货"态，采购订单也不会因退货回退状态。退货事实只以只读概览呈现，统计"退货量"要按本文件的申请 / 审核 / 处理三个量分别说清是哪一个。

## 六、常见错误

1. 用下单量算可退量 → 要以实际已出 / 已入库量为基数。
2. 把销售退货的"库存可用量 = 0"当成不可退 → 销售退货是入库方向，不受库存限制。
3. 采购退货只算来源入库量、不减可用库存 → 会超出实际能退的货。
4. 忽略已有退货单的占用 → 同一批货被重复退。
5. 占用口径不分状态：`SUBMITTED` 用申请量、`APPROVED` 及之后用审核量，混用会算错。
6. 拿 `total_amount` 当应退款或实际执行金额 → 它是申请快照。
7. 用 `processed_qty` 当合格量 → 它是实际处理总量（合格 + 不合格）。
8. 依赖 `handling_type` 做业务判断 → 后端只校验非空，没强制枚举。
9. 按单号前缀判断退货方向 → 历史号段并存，要用 `return_type`。
10. 用 `expected_execution_date` 当实际执行日，或用 `update_time` 归期 → 归期看 `approved_at` / `confirmed_at`。
11. 在 `return_order_item` 上加 `deleted = 0` → 明细表没有这一列。
12. 以为退货会把来源订单改成"已退货"态 → 退货不改来源单状态。
13. 退货仓库选了与原订单不同的仓 → 会被"退货仓库必须与原订单仓库一致"拒绝。

## 七、写 `instruction` 时的提醒

1. 算可退量必须先减占用，并写明占用按哪个状态口径取（申请量还是审核量）。
2. 分清方向：销售退货不受库存限制，采购退货受，两套公式不要套混。
3. 退货金额默认写"按审核通过数量 × 单价快照现场算"；只有明确要"申请金额"时才用退货单上的申请金额快照，并注明它是快照。
4. 统计退货量要说明是申请量、审核量还是实际处理量。
5. 时间归期写明用"审核时间"还是"实际执行（确认）时间"，不要用建单时间。
6. 过滤状态要写枚举字面量（`APPROVED`、`CANCELLED`），列名不写进 `instruction`。
7. 数字必须标单位（数量业务单位、金额分或元），同一需求内保持一致。