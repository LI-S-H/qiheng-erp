-- 交易金额统一为 BIGINT（按“元 × 100”的分值存储）。
-- 适用数据库：erp。执行前完成备份，并先发布能够读取 Long 金额的应用版本。
-- 本迁移仅扩容 INT 到 BIGINT，绝不对现有分值再次乘以 100；可重复执行。

ALTER TABLE customer
    MODIFY COLUMN credit_limit BIGINT NOT NULL DEFAULT 0 COMMENT '信用额度，放大100倍保存，18000000表示180000.00';

ALTER TABLE supplier_product
    MODIFY COLUMN latest_purchase_price BIGINT NULL DEFAULT NULL COMMENT '最近采购单价，放大100倍保存，3520表示35.20；尚未采购时为空';

ALTER TABLE purchase_order
    MODIFY COLUMN total_amount BIGINT NOT NULL DEFAULT 0 COMMENT '订单总金额，放大100倍保存，84480表示844.80';

ALTER TABLE purchase_order_item
    MODIFY COLUMN unit_price BIGINT NOT NULL DEFAULT 0 COMMENT '采购单价，放大100倍保存，3520表示35.20',
    MODIFY COLUMN total_amount BIGINT NOT NULL DEFAULT 0 COMMENT '明细金额，放大100倍保存，168960表示1689.60';

ALTER TABLE sales_order
    MODIFY COLUMN total_amount BIGINT NOT NULL DEFAULT 0 COMMENT '订单总金额，放大100倍保存，0表示0.00';

ALTER TABLE sales_order_item
    MODIFY COLUMN unit_price BIGINT NOT NULL DEFAULT 0 COMMENT '销售单价，放大100倍保存，0表示0.00',
    MODIFY COLUMN total_amount BIGINT NOT NULL DEFAULT 0 COMMENT '明细金额，放大100倍保存，0表示0.00';

ALTER TABLE return_order
    MODIFY COLUMN total_amount BIGINT NOT NULL DEFAULT 0 COMMENT '当前有效退货总金额，放大100倍保存，17600表示176.00';

ALTER TABLE return_order_item
    MODIFY COLUMN unit_price BIGINT NOT NULL DEFAULT 0 COMMENT '原采购或销售订单明细单价快照，放大100倍保存，3520表示35.20',
    MODIFY COLUMN total_amount BIGINT NOT NULL DEFAULT 0 COMMENT '当前有效明细金额，放大100倍保存，17600表示176.00';

-- 验收一：11 个目标字段必须全部为 BIGINT。
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'customer' AND COLUMN_NAME = 'credit_limit')
    OR (TABLE_NAME = 'supplier_product' AND COLUMN_NAME = 'latest_purchase_price')
    OR (TABLE_NAME = 'purchase_order' AND COLUMN_NAME = 'total_amount')
    OR (TABLE_NAME = 'purchase_order_item' AND COLUMN_NAME IN ('unit_price', 'total_amount'))
    OR (TABLE_NAME = 'sales_order' AND COLUMN_NAME = 'total_amount')
    OR (TABLE_NAME = 'sales_order_item' AND COLUMN_NAME IN ('unit_price', 'total_amount'))
    OR (TABLE_NAME = 'return_order' AND COLUMN_NAME = 'total_amount')
    OR (TABLE_NAME = 'return_order_item' AND COLUMN_NAME IN ('unit_price', 'total_amount')))
ORDER BY TABLE_NAME, ORDINAL_POSITION;

-- 验收二：主单金额必须等于明细金额汇总。结果必须全部为 0。
SELECT 'purchase_order_total_mismatch' AS check_name, COUNT(*) AS invalid_count
FROM purchase_order po
WHERE po.total_amount <> COALESCE((SELECT SUM(poi.total_amount)
    FROM purchase_order_item poi WHERE poi.purchase_order_id = po.id), 0)
UNION ALL
SELECT 'sales_order_total_mismatch', COUNT(*)
FROM sales_order so
WHERE so.total_amount <> COALESCE((SELECT SUM(soi.total_amount)
    FROM sales_order_item soi WHERE soi.sales_order_id = so.id), 0)
UNION ALL
SELECT 'return_order_total_mismatch', COUNT(*)
FROM return_order ro
WHERE ro.status <> 'CANCELLED'
  AND ro.total_amount <> COALESCE((SELECT SUM(roi.total_amount)
      FROM return_order_item roi WHERE roi.return_order_id = ro.id), 0);
