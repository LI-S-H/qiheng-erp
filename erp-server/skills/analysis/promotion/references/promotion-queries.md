# 促销查询语句

以下语句交给 `query_sql_data` 工具执行。字段名已按系统实际表结构核对，参数用冒号占位。

**单位提醒**：数量乘了 100（12400 表示 124 件）；金额统一按分存储（970 表示 9.70 元），包括 `reference_purchase_price` 和 `reference_sale_price`。

## 1. 历史销量

按月汇总销售确认出库量，用于估计基线。

```sql
SELECT
    DATE_FORMAT(so.approved_at, '%Y-%m') AS month,
    SUM(soi.outbound_qty) AS outbound_qty,
    COUNT(DISTINCT so.id) AS bill_count,
    SUM(soi.total_amount) / 100 AS outbound_amount
FROM sales_order_item soi
JOIN sales_order so ON so.id = soi.sales_order_id
WHERE soi.product_id = :productId
  AND so.deleted = 0
  AND so.status = 'OUTBOUND_DONE'
  AND so.approved_at >= DATE_SUB(CURDATE(), INTERVAL :months MONTH)
GROUP BY DATE_FORMAT(so.approved_at, '%Y-%m')
ORDER BY month;
```

- 只统计已全部出库的销售单，未出库和已取消不算
- 出库明细表没有单价，金额从销售明细的 `total_amount` 取，已经乘了 100
- `months` 默认取 6，去年同期至少取 12
- 某月没有记录不等于销量为 0，可能是缺货，判断基线时要看库存是否为 0

## 2. 历史成交价

查最近的实际成交单价，用于基线和活动后价格对比。

```sql
SELECT
    soi.unit_price,
    soi.quantity,
    so.approved_at,
    so.customer_name,
    so.warehouse_name
FROM sales_order_item soi
JOIN sales_order so ON so.id = soi.sales_order_id
WHERE soi.product_id = :productId
  AND so.deleted = 0
  AND so.status = 'OUTBOUND_DONE'
ORDER BY so.approved_at DESC
LIMIT 20;
```

- 明细单价乘了 100，展示时除以 100
- 参考售价不是成交价，不能直接当基线价格
- 结果为空说明没有成交历史，价格基线标未知

## 3. 当前库存与锁定

活动可承接量的起点。

```sql
SELECT
    ws.warehouse_id,
    ws.warehouse_name,
    ws.stock_qty,
    ws.locked_qty,
    ws.stock_qty - ws.locked_qty AS available_qty,
    p.quantity_precision,
    p.safety_stock_qty
FROM warehouse_stock ws
JOIN product p ON p.id = ws.product_id
WHERE ws.product_id = :productId
ORDER BY available_qty DESC;
```

- 可售量 = 库存 − 锁定
- 安全库存是否可动用，要看用户的活动政策

## 4. 活动期在途

活动期间能到货的采购，计入供给上限。

```sql
SELECT
    po.id AS purchase_order_id,
    po.purchase_no,
    po.status,
    po.expected_arrival_date,
    SUM(poi.quantity - poi.inbound_qty) AS pending_qty
FROM purchase_order_item poi
JOIN purchase_order po ON po.id = poi.purchase_order_id
WHERE poi.product_id = :productId
  AND po.deleted = 0
  AND po.status NOT IN ('INBOUND_DONE', 'CANCELLED')
GROUP BY po.id, po.purchase_no, po.status, po.expected_arrival_date
ORDER BY po.expected_arrival_date;
```

- 只有预计到货日在活动期内才计入供给上限
- 结果为空表示没有在途

## 5. 活动期已承诺订单

查活动期间未完成销售单的未出库数量，用于核对锁定构成和交付安排。

```sql
SELECT
    so.id AS sales_order_id,
    so.sales_no,
    so.customer_name,
    so.status,
    so.expected_delivery_date,
    SUM(soi.quantity - soi.outbound_qty) AS pending_qty
FROM sales_order_item soi
JOIN sales_order so ON so.id = soi.sales_order_id
WHERE soi.product_id = :productId
  AND so.deleted = 0
  AND so.status IN ('SUBMITTED', 'APPROVED', 'PARTIAL_OUTBOUND')
GROUP BY so.id, so.sales_no, so.customer_name, so.status, so.expected_delivery_date;
```

- 这些订单的货已经计入 `warehouse_stock.locked_qty`，本查询只用于核对锁定和交付安排，不参与供给上限扣减
- 如果这些订单也参加活动价，要单独说明，不能重复计算

## 6. 供货报价

只在用户明确同意时作为代理成本，不能当作实际成本。

```sql
SELECT
    sp.supplier_id,
    sp.quoted_purchase_price,
    sp.latest_purchase_price,
    sp.quote_valid_until,
    sp.last_purchase_at
FROM supplier_product sp
WHERE sp.product_id = :productId
  AND sp.deleted = 0
  AND sp.status = 1;
```

- 报价和成交价都乘了 100
- 用作代理成本时，输出里必须写"代理情景"，不能写"实际成本"

## 7. 活动期销量对比

复盘时查活动期和相邻正常期的销量。

```sql
SELECT
    CASE
        WHEN so.approved_at >= :startDate AND so.approved_at < :endDate THEN '活动期'
        ELSE '对照期'
    END AS period,
    SUM(soi.outbound_qty) AS outbound_qty,
    SUM(soi.total_amount) / 100 AS outbound_amount,
    COUNT(DISTINCT so.id) AS bill_count
FROM sales_order_item soi
JOIN sales_order so ON so.id = soi.sales_order_id
WHERE soi.product_id = :productId
  AND so.deleted = 0
  AND so.status = 'OUTBOUND_DONE'
  AND so.approved_at >= :compareStart
  AND so.approved_at < :compareEnd
GROUP BY period;
```

- 对照期长度要和活动期一致
- 用销售单审批日期划分期间，与活动起止日口径一致
- 没有未做活动的同类商品作对照时，前后对比只能描述差异，不能归因

## 查询顺序建议

1. 先查历史销量和成交价，确定基线
2. 再查库存、在途和已承诺量，算供给上限
3. 需要代理成本时查报价，并标明是代理情景
4. 复盘时查活动期和对照期销量

## 字段核对提示

| 表 | 字段 | 说明 |
|---|---|---|
| product | reference_sale_price | bigint 存分，不是成交价 |
| product | quantity_precision | 用于销量门槛取整 |
| sales_order_item | unit_price | 金额乘了 100 |
| sales_order | status | 已出库为 OUTBOUND_DONE |
| outbound_bill | status | 已确认为 CONFIRMED |
| outbound_bill_item | current_qty | 实际出库数量 |
| supplier_product | quoted_purchase_price | 金额乘了 100，只能作代理 |

查询报字段不存在时，先查 `information_schema.columns` 确认列名，不要反复换关联方式试错。