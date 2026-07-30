-- 将 supplier_product 从主数据快照表调整为纯供应商-产品关系表。
-- 适用于已经执行过 004_mvp_purchase.sql 的数据库；执行前请先完成数据库备份。

USE erp;

DELIMITER $$

DROP PROCEDURE IF EXISTS migrate_supplier_product_remove_master_snapshots$$

CREATE PROCEDURE migrate_supplier_product_remove_master_snapshots()
BEGIN
    DECLARE orphan_count BIGINT DEFAULT 0;
    DECLARE snapshot_column_count INT DEFAULT 0;
    DECLARE migration_marker_column_count INT DEFAULT 0;
    DECLARE price_data_type VARCHAR(32);
    DECLARE price_is_nullable VARCHAR(3);

    SELECT COUNT(1)
      INTO orphan_count
      FROM supplier_product supplier_product
      LEFT JOIN supplier supplier ON supplier.id = supplier_product.supplier_id AND supplier.deleted = 0
      LEFT JOIN product product ON product.id = supplier_product.product_id AND product.deleted = 0
     WHERE supplier_product.deleted = 0
       AND (supplier_product.supplier_id IS NULL
        OR supplier_product.product_id IS NULL
        OR supplier.id IS NULL
        OR product.id IS NULL);

    IF orphan_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'supplier_product 存在缺失或已删除的供应商/产品关联，已中止快照列删除';
    END IF;

    SELECT DATA_TYPE, IS_NULLABLE
      INTO price_data_type, price_is_nullable
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'supplier_product'
       AND column_name = 'latest_purchase_price';

    SELECT COUNT(1)
      INTO migration_marker_column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'supplier_product'
       AND column_name = '_price_amount_migrated';

    IF price_data_type IN ('decimal', 'numeric') THEN
        -- 先落一个持久化标记列：UPDATE 整体原子提交，即使 ALTER TABLE 前中断，重跑也不会重复乘以 100。
        IF migration_marker_column_count = 0 THEN
            ALTER TABLE supplier_product
                ADD COLUMN _price_amount_migrated TINYINT NOT NULL DEFAULT 0 COMMENT '供货关系价格单位迁移临时标记';
            SET migration_marker_column_count = 1;
        END IF;

        UPDATE supplier_product
           SET latest_purchase_price = CASE
                   WHEN latest_purchase_price IS NULL THEN NULL
                   ELSE ROUND(latest_purchase_price * 100)
               END,
               min_order_qty = ROUND(min_order_qty * 100),
               _price_amount_migrated = 1
         WHERE _price_amount_migrated = 0;

        ALTER TABLE supplier_product
            MODIFY COLUMN latest_purchase_price INT NULL DEFAULT NULL COMMENT '最近采购单价，放大100倍保存，3520表示35.20；尚未采购时为空',
            MODIFY COLUMN min_order_qty INT NOT NULL DEFAULT 0 COMMENT '最小起订量，放大100倍保存，1000表示10.00';
        SET price_data_type = 'int';
    ELSEIF price_data_type <> 'int' THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'supplier_product.latest_purchase_price 不是预期的 decimal 或 int 类型，已中止迁移';
    ELSEIF price_is_nullable = 'NO' THEN
        ALTER TABLE supplier_product
            MODIFY COLUMN latest_purchase_price INT NULL DEFAULT NULL COMMENT '最近采购单价，放大100倍保存，3520表示35.20；尚未采购时为空';
    END IF;

    -- 转换完成后再移除临时标记；若此前中断而类型已改为 int，本次会只做清理，不会再换算金额。
    IF price_data_type = 'int' AND migration_marker_column_count = 1 THEN
        ALTER TABLE supplier_product DROP COLUMN _price_amount_migrated;
    END IF;

    SELECT COUNT(1)
      INTO snapshot_column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'supplier_product'
       AND column_name IN ('supplier_code', 'supplier_name', 'product_code', 'product_name', 'unit_name');

    IF snapshot_column_count = 5 THEN
        ALTER TABLE supplier_product
            DROP COLUMN supplier_code,
            DROP COLUMN supplier_name,
            DROP COLUMN product_code,
            DROP COLUMN product_name,
            DROP COLUMN unit_name;
    ELSEIF snapshot_column_count <> 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = 'supplier_product 主数据快照列处于不完整状态，已中止迁移';
    END IF;
END$$

CALL migrate_supplier_product_remove_master_snapshots()$$
DROP PROCEDURE migrate_supplier_product_remove_master_snapshots$$

DELIMITER ;
