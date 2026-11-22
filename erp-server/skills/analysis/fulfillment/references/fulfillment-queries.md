# 订单履约查询语句

以下语句交给 `query_sql_data` 工具执行。字段名已按系统实际表结构核对，参数用冒号占位。

**单位提醒**：数量和金额都乘了 100（12400 表示 124 件、970 表示 9.70 元），计算和展示前除以 100。

## 1. 待发订单明细

查未发完的订单，含客户要求交付日。

```sql
SELECT
    so.id AS sales_order_id,
    so.sales_no,
    so.customer_name,
    so.warehouse_id,
    so.warehouse_name,
    so.status,
    so.expected_delivery_date,
    soi.product_id,
    soi.product_code,
    soi.product_name,
    soi.quantity - soi.outbound_qty AS pending_qty,
    soi.locked_qty
FROM sales_order_item soi
JOIN sales_order so ON so.id = soi.sales_order_id
WHERE so.deleted = 0
  AND so.status IN ('SUBMITTED', 'APPROVED', 'PARTIAL_OUTBOUND')
  AND so.expected_delivery_date <= :deadline
ORDER BY so.expected_delivery_date, so.sales_no;
```

- 待发量 = 订单数量 − 已出库数量
- 草稿和已取消不计入
- `locked_qty` 用于核对订单是否已预占库存
- `deadline` 取用户关心的截止日期

## 2. 按仓按产品汇总待发量

```sql
SELECT
    so.warehouse_id,
    so.warehouse_name,
    soi.product_id,
    soi.product_code,
    soi.product_name,
    SUM(soi.quantity - soi.outbound_qty) AS pending_qty,
    MIN(so.expected_delivery_date) AS earliest_delivery_date,
    COUNT(DISTINCT so.id) AS order_count
FROM sales_order_item soi
JOIN sales_order so ON so.id = soi.sales_order_id
WHERE so.deleted = 0
  AND so.status IN ('SUBMITTED', 'APPROVED', 'PARTIAL_OUTBOUND')
  AND so.expected_delivery_date <= :deadline
GROUP BY so.warehouse_id, so.warehouse_name, soi.product_id, soi.product_code, soi.product_name
ORDER BY earliest_delivery_date;
```

- 按仓库分组是关键，货在哪个仓决定能不能发
- 最早交付日用于排风险优先级

## 3. 各仓库存与锁定

```sql
SELECT
    ws.warehouse_id,
    ws.warehouse_name,
    ws.product_id,
    ws.product_code,
    ws.product_name,
    ws.stock_qty,
    ws.locked_qty,
    ws.stock_qty - ws.locked_qty AS available_qty
FROM warehouse_stock ws
WHERE ws.product_id IN (:productIds)
ORDER BY ws.warehouse_id, ws.product_id;
```

- 履约缺口用 `stock_qty` 判断，不是 `available_qty`，因为待发订单的货已经锁定在库存里
- `available_qty` 用于判断是否还有余量接新单

## 4. 在途采购

```sql
SELECT
    po.warehouse_id,
    po.warehouse_name,
    poi.product_id,
    po.expected_arrival_date,
    SUM(poi.quantity - poi.inbound_qty) AS pending_qty
FROM purchase_order_item poi
JOIN purchase_order po ON po.id = poi.purchase_order_id
WHERE poi.product_id IN (:productIds)
  AND po.deleted = 0
  AND po.status NOT IN ('INBOUND_DONE', 'CANCELLED')
GROUP BY po.warehouse_id, po.warehouse_name, poi.product_id, po.expected_arrival_date;
```

- 只有预计到货日早于交付日的才算有效供给
- 结果为空表示没有在途

## 5. 历史履约率

按出库确认日与客户要求交付日比较。

```sql
SELECT
    COUNT(*) AS total_orders,
    SUM(CASE WHEN DATE(ob.confirmed_at) <= so.expected_delivery_date THEN 1 ELSE 0 END) AS on_time_orders
FROM sales_order so
JOIN outbound_bill ob ON ob.source_type = 'SALES_ORDER' AND ob.source_id = so.id
WHERE so.deleted = 0
  AND ob.deleted = 0
  AND ob.status = 'CONFIRMED'
  AND so.status = 'OUTBOUND_DONE'
  AND ob.confirmed_at >= DATE_SUB(CURDATE(), INTERVAL :windowDays DAY);
```

- 出库单通过 `source_type = 'SALES_ORDER'` 加 `source_id` 关联销售单
- 按期判断用出库确认日，不是订单状态变更时间
- 结果样本少于 20 单时履约率只能作参考

## 6. 逾期订单

```sql
SELECT
    so.sales_no,
    so.customer_name,
    so.warehouse_name,
    so.expected_delivery_date,
    DATEDIFF(CURDATE(), so.expected_delivery_date) AS overdue_days,
    SUM(soi.quantity - soi.outbound_qty) AS pending_qty
FROM sales_order so
JOIN sales_order_item soi ON soi.sales_order_id = so.id
WHERE so.deleted = 0
  AND so.status IN ('SUBMITTED', 'APPROVED', 'PARTIAL_OUTBOUND')
  AND so.expected_delivery_date < CURDATE()
GROUP BY so.id, so.sales_no, so.customer_name, so.warehouse_name, so.expected_delivery_date
ORDER BY overdue_days DESC;
```

- 逾期天数从客户要求交付日算起
- 逾期订单优先处理，不和其他风险混排

## 7. 锁定核对

```sql
SELECT
    so.warehouse_id,
    so.warehouse_name,
    soi.product_id,
    SUM(soi.quantity - soi.outbound_qty) AS pending_qty,
    SUM(soi.locked_qty) AS order_locked_qty
FROM sales_order_item soi
JOIN sales_order so ON so.id = soi.sales_order_id
WHERE so.deleted = 0
  AND so.status IN ('SUBMITTED', 'APPROVED', 'PARTIAL_OUTBOUND')
GROUP BY so.warehouse_id, so.warehouse_name, soi.product_id;
```

- 订单锁定合计应与待发量接近
- 锁定明显少于待发量说明存在未预占订单，有超卖风险
- 锁定明显多于待发量说明有订单完成或取消后没有释放

## 查询顺序建议

1. 先查待发订单，确定范围和交付日
2. 按仓按产品汇总待发量
3. 查对应库存和锁定
4. 查在途，按交付日筛选
5. 需要判断系统性问题时查历史履约率
6. 需要处置时检查调拨可行性和采购在途

## 字段核对提示

| 表 | 字段 | 说明 |
|---|---|---|
| sales_order | status | 待发为 SUBMITTED、APPROVED、PARTIAL_OUTBOUND |
| sales_order | expected_delivery_date | 客户要求交付日 |
| sales_order_item | quantity / outbound_qty | 待发量 = 两者相减 |
| sales_order_item | locked_qty | 订单预占量 |
| outbound_bill | source_type | 销售出库来源为 SALES_ORDER |
| warehouse_stock | stock_qty / locked_qty | 履约用 stock_qty 判断 |
| purchase_order | expected_arrival_date | 在途到货日 |

查询报字段不存在时，先查 `information_schema.columns` 确认列名，不要反复换关联方式试错。