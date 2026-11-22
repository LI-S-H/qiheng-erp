# 调拨查询语句

以下语句交给 `query_sql_data` 工具执行。字段名已按系统实际表结构核对，参数用冒号占位，执行前替换成实际值。

**数量单位**：数量按库内口径存储，乘了 100（12400 表示 124 件）。计算和展示前除以 100。

## 1. 各仓库存与锁定

查某产品在所有仓的库存、锁定和净可用。

```sql
SELECT
    ws.warehouse_id,
    ws.warehouse_code,
    ws.warehouse_name,
    ws.stock_qty,
    ws.locked_qty,
    ws.stock_qty - ws.locked_qty AS available_qty
FROM warehouse_stock ws
WHERE ws.product_id = :productId
ORDER BY available_qty DESC;
```

- 净可用可能为负，为负说明该仓已被预占超出，不能当源仓
- 结果为空表示该产品没有库存记录，不是查询失败

## 2. 待出库销售需求

查各仓未完成销售单里该产品的未出库数量，用于确定缺口发生日和锁定构成。

```sql
SELECT
    so.warehouse_id,
    so.warehouse_name,
    so.id AS sales_order_id,
    so.sales_no,
    so.status,
    so.expected_delivery_date,
    SUM(soi.quantity - soi.outbound_qty) AS pending_qty
FROM sales_order_item soi
JOIN sales_order so ON so.id = soi.sales_order_id
WHERE soi.product_id = :productId
  AND so.deleted = 0
  AND so.status IN ('SUBMITTED', 'APPROVED', 'PARTIAL_OUTBOUND')
GROUP BY so.warehouse_id, so.warehouse_name, so.id, so.sales_no, so.status, so.expected_delivery_date
ORDER BY so.expected_delivery_date;
```

- 未出库数量 = 订单数量 − 已出库数量
- 已全部出库（`OUTBOUND_DONE`）和已取消（`CANCELLED`）不计入
- `expected_delivery_date` 是客户要求交付日，用于确定缺口发生日；为空时按业务惯例取今天，并在结果里说明
- 这些订单的货已经计入 `warehouse_stock.locked_qty`，本查询只用于确定缺口日和核对锁定，不参与可释放量或缺口计算

## 3. 在途采购

查各仓尚未入库的采购量和预计到货日。

```sql
SELECT
    po.warehouse_id,
    po.warehouse_name,
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
GROUP BY po.warehouse_id, po.warehouse_name, po.id, po.purchase_no, po.status, po.expected_arrival_date;
```

- 在途量 = 采购数量 − 已入库数量
- 只有预计到货日早于缺口日的在途才能抵消缺口
- 结果为空表示没有在途

## 4. 近 30 天出库速度

按仓统计该产品近 30 天的出库量，用于估计日需求。

```sql
SELECT
    ob.warehouse_id,
    ob.warehouse_name,
    SUM(obi.current_qty) AS outbound_qty,
    COUNT(DISTINCT ob.id) AS bill_count
FROM outbound_bill_item obi
JOIN outbound_bill ob ON ob.id = obi.outbound_bill_id
WHERE obi.product_id = :productId
  AND ob.deleted = 0
  AND ob.status = 'CONFIRMED'
  AND ob.confirmed_at >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)
GROUP BY ob.warehouse_id, ob.warehouse_name;
```

- 出库数量字段是明细表的 `current_qty`，主表没有 product_id
- 出库确认后的状态是 `CONFIRMED`
- 结果为空说明近 30 天没有出库，需求速度标样本不足

## 5. 产品安全库存与精度

查安全库存、数量精度和单位。

```sql
SELECT
    p.id AS product_id,
    p.product_code,
    p.product_name,
    p.unit_name,
    p.quantity_precision,
    p.safety_stock_qty
FROM product p
WHERE p.id = :productId
  AND p.deleted = 0;
```

- 精度用于调拨量取整，缺省时按 0 处理并说明
- 安全库存同样按数量口径存储，乘了 100，比较前要换算
- 安全库存为空时按 0 处理，并在结果里标注缺失

## 6. 各仓近期出入库流水

需要核对账面库存与流水是否一致时使用。

```sql
SELECT
    sb.warehouse_id,
    sb.warehouse_name,
    sbi.product_id,
    SUM(CASE WHEN sbi.change_qty > 0 THEN sbi.change_qty ELSE 0 END) AS in_qty,
    SUM(CASE WHEN sbi.change_qty < 0 THEN -sbi.change_qty ELSE 0 END) AS out_qty,
    SUM(sbi.change_qty) AS net_change
FROM stock_bill_item sbi
JOIN stock_bill sb ON sb.id = sbi.bill_id
WHERE sbi.product_id = :productId
  AND sb.create_time >= DATE_SUB(CURDATE(), INTERVAL 90 DAY)
GROUP BY sb.warehouse_id, sb.warehouse_name, sbi.product_id;
```

- 流水类型只有采购入库、销售出库、采购退货、销售退货、调整入库、调整出库，没有调拨类型
- 净变化与当前库存对不上时，先查一致性问题再给调拨建议

## 查询顺序建议

1. 先查库存与锁定，确定哪些仓有净可用
2. 再查待出库销售需求，确定目标仓和缺口日
3. 查在途采购，扣减能在缺口日前到货的量
4. 需要估计需求速度时查近 30 天出库
5. 最后查产品精度和安全库存，用于取整和保留量

## 字段核对提示

| 表 | 字段 | 说明 |
|---|---|---|
| warehouse_stock | stock_qty / locked_qty | 净可用 = 库存 − 锁定 |
| sales_order | status | 未完成状态为 SUBMITTED、APPROVED、PARTIAL_OUTBOUND |
| sales_order_item | quantity / outbound_qty | 待出库 = 两者相减 |
| purchase_order | status | 已完成是 INBOUND_DONE，已取消是 CANCELLED |
| purchase_order_item | quantity / inbound_qty | 在途 = 两者相减 |
| outbound_bill_item | current_qty | 实际出库数量 |
| stock_bill | bill_type | 只有采购入、销售出、采购退、销售退、调整入、调整出，没有调拨 |

查询报字段不存在时，先查 `information_schema.columns` 确认列名，不要反复换关联方式试错。