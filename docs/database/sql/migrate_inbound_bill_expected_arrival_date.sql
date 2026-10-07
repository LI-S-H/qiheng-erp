-- 为采购入库作业单增加采购订单预计到货日期快照。
-- 适用于已经执行过仓储、采购和仓储逻辑删除脚本的 MySQL 8 数据库。
-- 可重复执行；历史回填只处理系统生成的采购入库单。

USE erp;
SET NAMES utf8mb4;

DELIMITER $$

DROP PROCEDURE IF EXISTS migrate_inbound_bill_expected_arrival_date$$

CREATE PROCEDURE migrate_inbound_bill_expected_arrival_date()
BEGIN
    DECLARE expected_date_column_count INT DEFAULT 0;
    DECLARE deleted_column_count INT DEFAULT 0;
    DECLARE due_scan_index_count INT DEFAULT 0;

    SELECT COUNT(1)
      INTO expected_date_column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'inbound_bill'
       AND column_name = 'expected_arrival_date';

    IF expected_date_column_count = 0 THEN
        ALTER TABLE inbound_bill
            ADD COLUMN expected_arrival_date DATE DEFAULT NULL
                COMMENT '采购订单预计到货日期快照；仅系统生成的采购入库单有值'
                AFTER status;
    END IF;

    -- 已有字段也统一修正类型与中文注释，避免旧环境或错误编码留下脏元数据。
    ALTER TABLE inbound_bill
        MODIFY COLUMN expected_arrival_date DATE DEFAULT NULL
            COMMENT '采购订单预计到货日期快照；仅系统生成的采购入库单有值';

    -- 非采购入库不具备采购承诺日期，先清理历史错误值，再回填采购入库快照。
    UPDATE inbound_bill
       SET expected_arrival_date = NULL
     WHERE inbound_type <> 'PURCHASE_IN'
        OR source_type <> 'PURCHASE_ORDER';

    UPDATE inbound_bill ib
    INNER JOIN purchase_order po
            ON po.id = ib.source_id
       SET ib.expected_arrival_date = po.expected_arrival_date
     WHERE ib.inbound_type = 'PURCHASE_IN'
       AND ib.source_type = 'PURCHASE_ORDER';

    SELECT COUNT(1)
      INTO deleted_column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE()
       AND table_name = 'inbound_bill'
       AND column_name = 'deleted';

    SELECT COUNT(1)
      INTO due_scan_index_count
      FROM information_schema.statistics
     WHERE table_schema = DATABASE()
       AND table_name = 'inbound_bill'
       AND index_name = 'idx_inbound_bill_due_scan';

    IF deleted_column_count = 1 AND due_scan_index_count = 0 THEN
        ALTER TABLE inbound_bill
            ADD INDEX idx_inbound_bill_due_scan
                (inbound_type, status, deleted, expected_arrival_date);
    END IF;
END$$

CALL migrate_inbound_bill_expected_arrival_date()$$
DROP PROCEDURE migrate_inbound_bill_expected_arrival_date$$

DELIMITER ;

-- 校验：采购入库快照必须与来源采购订单一致，其他入库类型必须为空。
SELECT COUNT(*) AS purchase_inbound_mismatch_count
  FROM inbound_bill ib
  INNER JOIN purchase_order po ON po.id = ib.source_id
 WHERE ib.inbound_type = 'PURCHASE_IN'
   AND ib.source_type = 'PURCHASE_ORDER'
   AND NOT (ib.expected_arrival_date <=> po.expected_arrival_date);

SELECT COUNT(*) AS non_purchase_inbound_with_expected_date_count
  FROM inbound_bill
 WHERE expected_arrival_date IS NOT NULL
   AND (inbound_type <> 'PURCHASE_IN' OR source_type <> 'PURCHASE_ORDER');
