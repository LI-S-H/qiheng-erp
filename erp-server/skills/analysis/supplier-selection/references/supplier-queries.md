# 供应商查询语句

以下语句交给 `query_sql_data` 工具执行。字段名已按系统实际表结构核对，参数用冒号占位，执行前替换成实际值。

**数量单位**：数量和金额都按库内口径存储，数量乘了 100（3200 表示 32 件），金额乘了 100（970 表示 9.70 元）。计算和展示前统一除以 100。

## 1. 候选与报价

查某产品下所有可用供应商及其报价、评分。

```sql
SELECT
    sp.supplier_id,
    s.supplier_code,
    s.supplier_name,
    s.status,
    s.overall_score,
    s.delivery_score,
    s.quality_score,
    s.price_score,
    s.service_score,
    s.service_score_reason,
    sp.quoted_purchase_price,
    sp.quoted_price_reason,
    sp.quote_valid_until,
    sp.latest_purchase_price,
    sp.min_order_qty,
    sp.avg_delivery_days,
    sp.recommend_score,
    sp.score_basis_amount,
    sp.score_status,
    sp.last_purchase_at
FROM supplier_product sp
JOIN supplier s ON s.id = sp.supplier_id
WHERE sp.product_id = :productId
  AND sp.deleted = 0
  AND sp.status = 1
  AND s.deleted = 0
  AND s.status = 1
ORDER BY s.overall_score DESC;
```

- `sp.status` 为供货关系启用状态，停用的供货关系不算候选
- `quote_valid_until` 早于当天时，该报价只作参考
- `score_status` 为 NOT_READY 时，评分样本不足，不能当结论依据

## 2. 历史交期

查候选供应商近 180 天已完成采购单的实际交期。

```sql
SELECT
    po.supplier_id,
    po.id AS order_id,
    po.purchase_no,
    po.status,
    po.approved_at,
    po.fully_received_at,
    po.expected_arrival_date,
    po.cancel_affects_delivery_score,
    DATEDIFF(po.fully_received_at, po.approved_at) AS lead_days,
    CASE
        WHEN po.expected_arrival_date IS NOT NULL
             AND DATE(po.fully_received_at) > po.expected_arrival_date
        THEN 1 ELSE 0
    END AS overdue
FROM purchase_order po
WHERE po.supplier_id IN (:supplierIds)
  AND po.status = 'INBOUND_DONE'
  AND po.fully_received_at >= DATE_SUB(CURDATE(), INTERVAL 180 DAY)
  AND po.deleted = 0;
```

- 已完成状态的枚举值是 `INBOUND_DONE`，不是 COMPLETED
- 用 `approved_at` 作下单日，不用 `create_time`，避免草稿停留时间干扰
- 只取已完全入库的单，未完成的单独在途查询里看

## 3. 质检金额

查候选供应商近 180 天的合格与不合格数量，用于计算质量分和趋势。

```sql
SELECT
    po.supplier_id,
    ibi.product_id,
    SUM(ibi.qualified_qty * ibi.unit_price) AS qualified_amount,
    SUM(ibi.defective_qty * ibi.unit_price) AS defective_amount,
    COUNT(*) AS line_count
FROM inbound_bill_item ibi
JOIN inbound_bill ib ON ib.id = ibi.inbound_bill_id
JOIN purchase_order po ON po.id = ib.source_id AND ib.source_type = 'PURCHASE_ORDER'
WHERE po.supplier_id IN (:supplierIds)
  AND ib.confirmed_at >= DATE_SUB(CURDATE(), INTERVAL 180 DAY)
  AND ib.deleted = 0
GROUP BY po.supplier_id, ibi.product_id;
```

- 入库单通过 `source_type = 'PURCHASE_ORDER'` 加 `source_id` 关联采购单，没有 `source_order_id` 列
- 入库确认时间用 `ib.confirmed_at`，不用 `create_time`
- 质量分按金额算：合格金额 = 合格数量 × `ibi.unit_price`，不能只用数量，否则与系统评分口径不一致
- 数量和单价都乘了 100，乘积放大 10000 倍，但比值不受影响，不用还原

### 质量趋势

把窗口改成长度和起点不同的两段，分别执行一次：

- 最近窗口：`>= DATE_SUB(CURDATE(), INTERVAL 180 DAY)`
- 上一窗口：`>= DATE_SUB(CURDATE(), INTERVAL 360 DAY) AND < DATE_SUB(CURDATE(), INTERVAL 180 DAY)`

两段分别算质量分再比较。

## 4. 采购份额与依赖度

查某品类近 12 个月各供应商的采购金额，用于计算集中度和依赖度。

```sql
SELECT
    po.supplier_id,
    s.supplier_name,
    SUM(poi.total_amount) AS purchase_amount,
    COUNT(DISTINCT po.id) AS order_count
FROM purchase_order po
JOIN purchase_order_item poi ON poi.purchase_order_id = po.id
JOIN product p ON p.id = poi.product_id
JOIN supplier s ON s.id = po.supplier_id
WHERE p.category_id = :categoryId
  AND po.status = 'INBOUND_DONE'
  AND po.approved_at >= DATE_SUB(CURDATE(), INTERVAL 12 MONTH)
  AND po.deleted = 0
GROUP BY po.supplier_id, s.supplier_name
ORDER BY purchase_amount DESC;
```

供应商依赖度用一次查询同时取分子和分母，不要拆成两次：

```sql
SELECT
    (SELECT SUM(total_amount) FROM purchase_order
      WHERE supplier_id = :supplierId AND deleted = 0
        AND approved_at >= DATE_SUB(CURDATE(), INTERVAL 12 MONTH)) AS supplier_amount,
    (SELECT SUM(total_amount) FROM purchase_order
      WHERE deleted = 0
        AND approved_at >= DATE_SUB(CURDATE(), INTERVAL 12 MONTH)) AS total_amount;
```

- 依赖度 = 供应商金额 ÷ 全部金额
- 品类维度的份额和单品维度的占比要分开算，不能混用

## 5. 库存与出库速度

查产品当前库存余额，判断能否覆盖到下次到货。

```sql
SELECT
    ws.warehouse_id,
    ws.warehouse_name,
    ws.stock_qty,
    ws.locked_qty
FROM warehouse_stock ws
WHERE ws.product_id = :productId
ORDER BY ws.stock_qty DESC;
```

查近 6 个月按月出库量，估算月均出库速度。

```sql
SELECT
    DATE_FORMAT(ob.confirmed_at, '%Y-%m') AS month,
    SUM(obi.current_qty) AS outbound_qty,
    COUNT(DISTINCT ob.id) AS bill_count
FROM outbound_bill_item obi
JOIN outbound_bill ob ON ob.id = obi.outbound_bill_id
WHERE obi.product_id = :productId
  AND ob.deleted = 0
  AND ob.status = 'CONFIRMED'
  AND ob.confirmed_at >= DATE_SUB(CURDATE(), INTERVAL 6 MONTH)
GROUP BY DATE_FORMAT(ob.confirmed_at, '%Y-%m')
ORDER BY month;
```

- 出库数量字段是 `outbound_bill_item.current_qty`，出库单主表没有 product_id，不要直接从主表查
- 出库确认后的状态值是 `CONFIRMED`
- 出库单口径查不到时再退回库存流水，不要一开始就写两条

库存流水核对口径：

```sql
SELECT
    SUM(CASE WHEN sbi.change_qty < 0 THEN -sbi.change_qty ELSE 0 END) AS outflow_qty,
    SUM(sbi.change_qty) AS net_change_qty,
    COUNT(*) AS line_count
FROM stock_bill_item sbi
JOIN stock_bill sb ON sb.id = sbi.bill_id
WHERE sbi.product_id = :productId
  AND sb.create_time >= DATE_SUB(CURDATE(), INTERVAL 6 MONTH);
```

## 6. 在途采购

查该产品尚未完成入库的采购量和预计到货日。

```sql
SELECT
    po.id AS order_id,
    po.purchase_no,
    po.status,
    po.expected_arrival_date,
    SUM(poi.quantity - poi.inbound_qty) AS pending_qty
FROM purchase_order_item poi
JOIN purchase_order po ON po.id = poi.purchase_order_id
WHERE poi.product_id = :productId
  AND po.deleted = 0
  AND po.status NOT IN ('INBOUND_DONE', 'CANCELLED')
GROUP BY po.id, po.purchase_no, po.status, po.expected_arrival_date;
```

- 在途量 = 采购数量 − 已入库数量
- 结果为空表示没有在途，不是查询失败，不需要换口径重查

## 7. 评分变更记录

需要解释评分为什么变化时查变更日志。

```sql
SELECT
    log.supplier_id,
    log.metric_type,
    log.before_value,
    log.after_value,
    log.change_source,
    log.create_time
FROM supplier_score_change_log log
WHERE log.supplier_id IN (:supplierIds)
  AND log.create_time >= DATE_SUB(CURDATE(), INTERVAL 180 DAY)
ORDER BY log.create_time DESC;
```

- 表名和字段以 `SupplierScoreChangeLog` 实体为准

## 查询顺序建议

1. 先查候选与报价，确定候选数量
2. 再按候选 ID 批量查交期和质检，避免逐个供应商查
3. 需要品类风险时查采购份额，依赖度用一条查询取分子分母
4. 需要判断断供影响时，查库存余额、出库速度、在途采购
5. 评分异常时再查变更记录

候选只有一家时，第 3 步的集中度指数、加权排序、敏感性分析都不做，只给该供应商的采购占比。

## 字段核对提示

以下字段已核对，可以直接使用：

| 表 | 字段 | 说明 |
|---|---|---|
| purchase_order | status | 已完成状态为 `INBOUND_DONE` |
| inbound_bill | source_type / source_id | 来源类型 `PURCHASE_ORDER`，来源单据 ID |
| inbound_bill | confirmed_at | 入库确认时间 |
| inbound_bill_item | qualified_qty / defective_qty | 合格与不合格数量 |
| outbound_bill | status | 出库确认后为 `CONFIRMED` |
| outbound_bill_item | current_qty | 实际出库数量，主表无 product_id |
| purchase_order_item | quantity / inbound_qty | 在途量用两者相减 |
| warehouse_stock | stock_qty / locked_qty | 可用量 = 库存 − 锁定 |

查询报字段不存在时，先查 `information_schema.columns` 确认列名，再执行主查询；不要反复换关联方式试错。