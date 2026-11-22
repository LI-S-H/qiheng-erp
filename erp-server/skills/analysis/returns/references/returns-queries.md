# 退货查询语句

以下语句交给 `query_sql_data` 工具执行。字段名已按系统实际表结构核对，参数用冒号占位。

**单位提醒**：数量和金额都乘了 100（12400 表示 124 件、970 表示 9.70 元），计算和展示前除以 100。

## 1. 退货汇总

按类型统计窗口内的退货笔数、数量和金额。

```sql
SELECT
    r.return_type,
    COUNT(DISTINCT r.id) AS order_count,
    SUM(ri.requested_qty) AS requested_qty,
    SUM(ri.processed_qty) AS processed_qty,
    SUM(ri.total_amount) AS return_amount,
    SUM(CASE WHEN r.status = 'COMPLETED' THEN ri.processed_qty ELSE 0 END) AS completed_qty
FROM return_order r
JOIN return_order_item ri ON ri.return_order_id = r.id
WHERE r.deleted = 0
  AND r.status <> 'CANCELLED'
  AND r.create_time >= DATE_SUB(CURDATE(), INTERVAL :windowDays DAY)
GROUP BY r.return_type;
```

- `PURCHASE_RETURN` 是采购退货，`SALES_RETURN` 是销售退货，两类分开统计
- `requested_qty` 是申请量，`processed_qty` 是已执行量，两者要分别给出
- 已取消的退货单不计入

## 2. 原因分布

按原因编码分组，并保留原始编码便于识别历史值。

```sql
SELECT
    r.return_type,
    CASE WHEN r.reason_code = 'QUALITY' THEN 'QUALITY_ISSUE' ELSE r.reason_code END AS reason_code,
    COUNT(DISTINCT r.id) AS order_count,
    SUM(ri.requested_qty) AS requested_qty,
    SUM(ri.total_amount) AS return_amount
FROM return_order r
JOIN return_order_item ri ON ri.return_order_id = r.id
WHERE r.deleted = 0
  AND r.status <> 'CANCELLED'
  AND r.create_time >= DATE_SUB(CURDATE(), INTERVAL :windowDays DAY)
GROUP BY r.return_type, reason_code
ORDER BY return_amount DESC;
```

- `QUALITY` 归一为 `QUALITY_ISSUE`
- 历史数据里销售退货常用 `CUSTOMER_RETURN`，不在当前枚举内，保留原值并标注是历史编码
- 原因由人工填写，分组结论只作方向参考

## 3. 供应商集中度

采购退货按供应商汇总。

```sql
SELECT
    r.party_id,
    r.party_name,
    COUNT(DISTINCT r.id) AS order_count,
    SUM(ri.requested_qty) AS return_qty,
    SUM(ri.total_amount) AS return_amount
FROM return_order r
JOIN return_order_item ri ON ri.return_order_id = r.id
WHERE r.deleted = 0
  AND r.return_type = 'PURCHASE_RETURN'
  AND r.status <> 'CANCELLED'
  AND r.create_time >= DATE_SUB(CURDATE(), INTERVAL :windowDays DAY)
GROUP BY r.party_id, r.party_name
ORDER BY return_amount DESC;
```

- 份额 = 该供应商退货金额 ÷ 退货总额
- 集中度指数 = Σ 份额²
- 单个供应商少于 5 单时不给集中度结论

## 4. 产品集中度

按产品统计退货，定位问题产品。

```sql
SELECT
    ri.product_id,
    ri.product_code,
    ri.product_name,
    SUM(ri.requested_qty) AS return_qty,
    SUM(ri.total_amount) AS return_amount,
    COUNT(DISTINCT r.id) AS order_count
FROM return_order r
JOIN return_order_item ri ON ri.return_order_id = r.id
WHERE r.deleted = 0
  AND r.status <> 'CANCELLED'
  AND r.create_time >= DATE_SUB(CURDATE(), INTERVAL :windowDays DAY)
GROUP BY ri.product_id, ri.product_code, ri.product_name
ORDER BY return_amount DESC
LIMIT 20;
```

- 前几项占比用累计份额表示
- 与采购或销售数量对比后，才能判断是产品问题还是批次问题

## 5. 退货率

采购退货率用同期采购入库做分母。

```sql
SELECT
    (SELECT SUM(ri.requested_qty) FROM return_order r
       JOIN return_order_item ri ON ri.return_order_id = r.id
      WHERE r.deleted = 0 AND r.return_type = 'PURCHASE_RETURN'
        AND r.status <> 'CANCELLED'
        AND r.create_time >= DATE_SUB(CURDATE(), INTERVAL :windowDays DAY)) AS return_qty,
    (SELECT SUM(ibi.qualified_qty + ibi.defective_qty) FROM inbound_bill_item ibi
       JOIN inbound_bill ib ON ib.id = ibi.inbound_bill_id
      WHERE ib.deleted = 0 AND ib.status = 'CONFIRMED'
        AND ib.inbound_type = 'PURCHASE_IN'
        AND ib.confirmed_at >= DATE_SUB(CURDATE(), INTERVAL :windowDays DAY)) AS inbound_qty;
```

销售退货率把类型换成 `SALES_RETURN`，分母换成出库明细（`outbound_bill_item.current_qty`，条件 `outbound_type = 'SALES_OUT'`）。

- 分子分母窗口必须一致
- 分母为 0 时只给退货量，不给比率

## 6. 月度趋势

按创建月份统计退货量，用于看变化方向。

```sql
SELECT
    DATE_FORMAT(r.create_time, '%Y-%m') AS month,
    r.return_type,
    SUM(ri.requested_qty) AS return_qty,
    SUM(ri.total_amount) AS return_amount,
    COUNT(DISTINCT r.id) AS order_count
FROM return_order r
JOIN return_order_item ri ON ri.return_order_id = r.id
WHERE r.deleted = 0
  AND r.status <> 'CANCELLED'
  AND r.create_time >= DATE_SUB(CURDATE(), INTERVAL :months MONTH)
GROUP BY DATE_FORMAT(r.create_time, '%Y-%m'), r.return_type
ORDER BY month;
```

- 对比期长度一致
- 退货量上升要结合同期采购或销售规模，不能只看绝对量

## 7. 执行状态分布

看有多少退货还没执行完。

```sql
SELECT
    r.return_type,
    r.status,
    COUNT(DISTINCT r.id) AS order_count,
    SUM(ri.requested_qty) AS requested_qty,
    SUM(ri.processed_qty) AS processed_qty
FROM return_order r
JOIN return_order_item ri ON ri.return_order_id = r.id
WHERE r.deleted = 0
  AND r.create_time >= DATE_SUB(CURDATE(), INTERVAL :windowDays DAY)
GROUP BY r.return_type, r.status;
```

- 状态枚举：DRAFT、SUBMITTED、APPROVED、PARTIAL_EXECUTED、COMPLETED、CANCELLED
- 待执行量 = 申请量 − 已执行量

## 查询顺序建议

1. 先查汇总，确定退货规模和样本量
2. 再查原因分布和集中度
3. 需要比率时查退货率
4. 需要趋势时查月度
5. 需要判断是否影响供应时查执行状态

## 字段核对提示

| 表 | 字段 | 说明 |
|---|---|---|
| return_order | return_type | PURCHASE_RETURN 或 SALES_RETURN |
| return_order | reason_code | 有历史旧值，QUALITY 需归一 |
| return_order_item | requested_qty / processed_qty | 申请量与已执行量 |
| return_order | status | DRAFT 到 COMPLETED 六种状态 |
| return_order | party_id / party_name | 供应商或客户 |
| inbound_bill | inbound_type | 采购入库为 PURCHASE_IN |
| outbound_bill | outbound_type | 销售出库为 SALES_OUT |

查询报字段不存在时，先查 `information_schema.columns` 确认列名，不要反复换关联方式试错。