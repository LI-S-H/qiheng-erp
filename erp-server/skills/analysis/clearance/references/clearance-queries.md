# 清仓查询语句

以下语句交给 `query_sql_data` 工具执行。字段名已按系统实际表结构核对，参数用冒号占位。

**单位提醒**：数量乘了 100（12400 表示 124 件）；金额统一按分存储（970 表示 9.70 元），包括 `product.reference_purchase_price` 和 `reference_sale_price`。计算和展示前除以 100。

## 1. 库存与锁定

查候选产品的库存、锁定和未预占量。

```sql
SELECT
    ws.warehouse_id,
    ws.warehouse_name,
    ws.product_id,
    p.product_code,
    p.product_name,
    p.unit_name,
    p.quantity_precision,
    p.safety_stock_qty,
    ws.stock_qty,
    ws.locked_qty,
    ws.stock_qty - ws.locked_qty AS available_qty
FROM warehouse_stock ws
JOIN product p ON p.id = ws.product_id
WHERE ws.product_id = :productId
  AND p.deleted = 0
ORDER BY available_qty DESC;
```

- 未预占量 = 库存 − 锁定，是后续所有测算的起点
- 结果为空表示没有库存记录

## 2. 出库序列与最近出库日

查窗口内销售确认出库量和最近一次出库日。

```sql
SELECT
    ob.warehouse_id,
    ob.warehouse_name,
    SUM(obi.current_qty) AS outbound_qty,
    COUNT(DISTINCT ob.id) AS bill_count,
    MAX(ob.confirmed_at) AS last_outbound_at,
    DATEDIFF(CURDATE(), DATE(MAX(ob.confirmed_at))) AS days_since_outbound
FROM outbound_bill_item obi
JOIN outbound_bill ob ON ob.id = obi.outbound_bill_id
WHERE obi.product_id = :productId
  AND ob.deleted = 0
  AND ob.status = 'CONFIRMED'
  AND ob.confirmed_at >= DATE_SUB(CURDATE(), INTERVAL :windowDays DAY)
GROUP BY ob.warehouse_id, ob.warehouse_name;
```

- 只统计销售确认出库，采购退货和库存调整不算市场需求
- `windowDays` 取用户给的观察窗口，默认 90
- 结果为空说明窗口内没有销售出库，无出库间隔标"窗口内无销售"
- 最近出库日要在完整窗口内取，不能只看窗口内

### 不带窗口的最近出库日

窗口内无出库时，用这条确认最后一次出库发生在什么时候。

```sql
SELECT
    ob.warehouse_id,
    MAX(ob.confirmed_at) AS last_outbound_at,
    DATEDIFF(CURDATE(), DATE(MAX(ob.confirmed_at))) AS days_since_outbound
FROM outbound_bill_item obi
JOIN outbound_bill ob ON ob.id = obi.outbound_bill_id
WHERE obi.product_id = :productId
  AND ob.deleted = 0
  AND ob.status = 'CONFIRMED'
GROUP BY ob.warehouse_id;
```

- 查询结果可以是几个月甚至更早，直接展示间隔天数
- 没有任何出库记录时返回空，说明从未出库，不要写成无限积压

## 3. 库存金额

按参考采购价估算资金占用。

```sql
SELECT
    p.id AS product_id,
    p.product_code,
    p.product_name,
    p.reference_purchase_price,
    p.reference_sale_price,
    ws.stock_qty,
    ws.stock_qty * p.reference_purchase_price / 10000 AS stock_amount
FROM warehouse_stock ws
JOIN product p ON p.id = ws.product_id
WHERE ws.product_id = :productId;
```

- `stock_qty` 和参考价都乘了 100，乘积除以 10000 得到元金额
- 这是参考价口径，不是实际成本；没有销货成本时不要叫库存成本
- 参考采购价为空的商品，金额标未知，不按 0 计算

## 4. 采购来源与可退供量

查该产品从哪些供应商采购过、已入库多少，用于判断退供可行性。

```sql
SELECT
    po.supplier_id,
    po.supplier_name,
    po.id AS purchase_order_id,
    po.purchase_no,
    po.status,
    po.fully_received_at,
    poi.quantity,
    poi.inbound_qty,
    poi.unit_price,
    poi.total_amount
FROM purchase_order_item poi
JOIN purchase_order po ON po.id = poi.purchase_order_id
WHERE poi.product_id = :productId
  AND po.deleted = 0
  AND po.status = 'INBOUND_DONE'
ORDER BY po.fully_received_at DESC;
```

- 可退供只可能来自实际采购入库过的订单
- 结果为空表示没有采购来源，退供不可行
- 退货期限系统没有，需要用户提供

## 5. 历史退货占用

查该产品已经发生的采购退货，避免重复退。

```sql
SELECT
    r.id AS return_order_id,
    r.return_no,
    r.status,
    r.return_type,
    r.source_order_id,
    r.create_time,
    SUM(ri.requested_qty) AS requested_qty,
    SUM(ri.processed_qty) AS processed_qty
FROM return_order r
JOIN return_order_item ri ON ri.return_order_id = r.id
WHERE ri.product_id = :productId
  AND r.return_type = 'PURCHASE_RETURN'
  AND r.status <> 'CANCELLED'
GROUP BY r.id, r.return_no, r.status, r.return_type, r.source_order_id, r.create_time;
```

- 可退量 = 已入库数量 − 已退数量（含未完成的退货单占用）
- 状态枚举：DRAFT、SUBMITTED、APPROVED、PARTIAL_EXECUTED、COMPLETED、CANCELLED

## 6. 其他仓需求

判断能否调拨去化，口径与调拨技能一致。

```sql
SELECT
    ws.warehouse_id,
    ws.warehouse_name,
    ws.stock_qty - ws.locked_qty AS available_qty
FROM warehouse_stock ws
WHERE ws.product_id = :productId
ORDER BY available_qty;
```

- 目标仓的缺口按 `transfer` 技能的查询取，注意锁定已包含待出库订单，不要重复扣
- 本技能只判断"有没有别的仓能吃下"，具体调拨量交给 transfer 计算

## 7. 最近成交价

用于判断当前售价和最近实际成交的差距。

```sql
SELECT
    soi.unit_price,
    so.approved_at,
    so.customer_name
FROM sales_order_item soi
JOIN sales_order so ON so.id = soi.sales_order_id
WHERE soi.product_id = :productId
  AND so.status = 'OUTBOUND_DONE'
  AND so.deleted = 0
ORDER BY so.approved_at DESC
LIMIT 10;
```

- 明细单价乘了 100，展示时除以 100
- 最近成交价是历史事实，不是本次可执行价格

## 查询顺序建议

1. 先查库存与锁定，确定未预占量
2. 再查出库序列和最近出库日，算速度与覆盖天数
3. 需要金额时查库存金额
4. 用户考虑退供时查采购来源和历史退货
5. 用户考虑调拨时查其他仓库存
6. 需要解释价格时查最近成交价

## 字段核对提示

| 表 | 字段 | 说明 |
|---|---|---|
| product | reference_purchase_price | bigint 存分，与其他金额一致 |
| product | reference_sale_price | bigint 存分，与其他金额一致 |
| warehouse_stock | stock_qty / locked_qty | 数量乘了 100 |
| sales_order_item | unit_price | 金额乘了 100 |
| sales_order | status | 已出库为 OUTBOUND_DONE |
| outbound_bill | status | 已确认为 CONFIRMED |
| purchase_order | status | 已入库为 INBOUND_DONE |
| return_order | return_type | 采购退货为 PURCHASE_RETURN |

查询报字段不存在时，先查 `information_schema.columns` 确认列名，不要反复换关联方式试错。