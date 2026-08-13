-- 采购、退货数量统一为 BIGINT，并为采购明细补齐数量精度快照。
-- 适用数据库：erp；执行前请完成备份，并确认应用程序已同步切换 Long / BIGINT 契约。
-- 本脚本不做乘除换算：现有数量已经是“真实业务值 × 100”的整数。
-- 可重复执行：已完成的类型扩容可安全重跑；仅为 quantity_precision 为空的历史采购明细回填快照。

-- 1. 为旧采购明细添加可回填的快照列。新建表的初始化 DDL 已直接定义为 NOT NULL。
SET @column_exists := (
    SELECT COUNT(*)
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'purchase_order_item'
      AND COLUMN_NAME = 'quantity_precision'
);
SET @sql := IF(@column_exists = 0,
    'ALTER TABLE purchase_order_item ADD COLUMN quantity_precision TINYINT NULL DEFAULT NULL COMMENT ''数量小数位快照：0-2，下单时从 product.quantity_precision 固化'' AFTER unit_name',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 2. 历史明细只在没有快照时按当前可取得的产品精度回填；重跑不得覆盖已固化的历史值。
UPDATE purchase_order_item poi
JOIN product p ON p.id = poi.product_id
SET poi.quantity_precision = p.quantity_precision
WHERE poi.quantity_precision IS NULL;

-- 3. 缺少产品或产品精度的数据不能静默写成 0；先修复数据再继续执行后续 ALTER。
DROP PROCEDURE IF EXISTS assert_purchase_item_precision_backfilled;
DELIMITER $$
CREATE PROCEDURE assert_purchase_item_precision_backfilled()
BEGIN
    IF EXISTS (SELECT 1 FROM purchase_order_item WHERE quantity_precision IS NULL) THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '存在无法从 product 回填 quantity_precision 的采购明细，迁移终止';
    END IF;
END$$
DELIMITER ;
CALL assert_purchase_item_precision_backfilled();
DROP PROCEDURE assert_purchase_item_precision_backfilled;

-- 4. 扩容全部业务数量并收紧采购精度快照定义。
ALTER TABLE supplier_product
    MODIFY COLUMN min_order_qty BIGINT NOT NULL DEFAULT 0 COMMENT '最小起订量，放大100倍保存，1000表示10.00';

ALTER TABLE purchase_order_item
    MODIFY COLUMN quantity_precision TINYINT NOT NULL DEFAULT 0 COMMENT '数量小数位快照：0-2，下单时从 product.quantity_precision 固化',
    MODIFY COLUMN quantity BIGINT NOT NULL DEFAULT 0 COMMENT '采购数量，放大100倍保存，2400表示24.00',
    MODIFY COLUMN inbound_qty BIGINT NOT NULL DEFAULT 0 COMMENT '已入库数量，放大100倍保存，900表示9.00';

ALTER TABLE return_order_item
    MODIFY COLUMN source_fulfilled_qty BIGINT NOT NULL DEFAULT 0 COMMENT '创建退货明细时原订单累计已出库或已入库数量快照，放大100倍保存',
    MODIFY COLUMN requested_qty BIGINT NOT NULL DEFAULT 0 COMMENT '申请退货数量，放大100倍保存，500表示5.00',
    MODIFY COLUMN approved_qty BIGINT NOT NULL DEFAULT 0 COMMENT '审核通过数量，放大100倍保存',
    MODIFY COLUMN processed_qty BIGINT NOT NULL DEFAULT 0 COMMENT '仓库累计确认的实际处理总量，放大100倍保存';

-- 5. 为旧库补齐采购精度检查约束；通过名称判断，避免重复执行失败。
SET @constraint_exists := (
    SELECT COUNT(*)
    FROM information_schema.TABLE_CONSTRAINTS
    WHERE CONSTRAINT_SCHEMA = DATABASE()
      AND TABLE_NAME = 'purchase_order_item'
      AND CONSTRAINT_NAME = 'chk_purchase_order_item_quantity_precision'
);
SET @sql := IF(@constraint_exists = 0,
    'ALTER TABLE purchase_order_item ADD CONSTRAINT chk_purchase_order_item_quantity_precision CHECK (quantity_precision BETWEEN 0 AND 2)',
    'SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 6. 迁移后验收：异常计数必须全部为 0，字段类型必须全部为 bigint，采购精度快照不得为空。
SELECT 'invalid_purchase_item_count' AS check_name, COUNT(*) AS invalid_count
FROM purchase_order_item
WHERE quantity_precision NOT BETWEEN 0 AND 2
   OR quantity < 0 OR inbound_qty < 0 OR inbound_qty > quantity
UNION ALL
SELECT 'invalid_return_item_count', COUNT(*)
FROM return_order_item
WHERE source_fulfilled_qty <= 0 OR requested_qty <= 0 OR requested_qty > source_fulfilled_qty
   OR approved_qty < 0 OR approved_qty > requested_qty
   OR processed_qty < 0 OR processed_qty > approved_qty
UNION ALL
SELECT 'missing_purchase_precision_count', COUNT(*)
FROM purchase_order_item
WHERE quantity_precision IS NULL;

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND ((TABLE_NAME = 'purchase_order_item' AND COLUMN_NAME IN ('quantity_precision', 'quantity', 'inbound_qty'))
    OR (TABLE_NAME = 'return_order_item' AND COLUMN_NAME IN ('source_fulfilled_qty', 'requested_qty', 'approved_qty', 'processed_qty'))
    OR (TABLE_NAME = 'supplier_product' AND COLUMN_NAME = 'min_order_qty'))
ORDER BY TABLE_NAME, ORDINAL_POSITION;
