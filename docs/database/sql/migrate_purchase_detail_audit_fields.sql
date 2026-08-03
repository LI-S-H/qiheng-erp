-- 已部署库迁移：采购详情所需的提交人与主数据最后维护人。
-- 可重复执行；不会为历史提交记录猜测提交人，历史详情会按空值兜底展示。
USE erp;

DELIMITER $$

DROP PROCEDURE IF EXISTS migrate_purchase_detail_audit_fields$$

CREATE PROCEDURE migrate_purchase_detail_audit_fields()
BEGIN
    DECLARE column_count INT DEFAULT 0;

    SELECT COUNT(1) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'purchase_order' AND column_name = 'submitted_by_id';
    IF column_count = 0 THEN
        ALTER TABLE purchase_order ADD COLUMN submitted_by_id BIGINT DEFAULT NULL COMMENT '提交人ID' AFTER submitted_at;
    END IF;

    SELECT COUNT(1) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'purchase_order' AND column_name = 'submitted_by_name';
    IF column_count = 0 THEN
        ALTER TABLE purchase_order ADD COLUMN submitted_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '提交人姓名' AFTER submitted_by_id;
    END IF;

    SELECT COUNT(1) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier' AND column_name = 'updated_by_id';
    IF column_count = 0 THEN
        ALTER TABLE supplier ADD COLUMN updated_by_id BIGINT DEFAULT NULL COMMENT '最后维护人ID' AFTER update_time;
    END IF;

    SELECT COUNT(1) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier' AND column_name = 'updated_by_name';
    IF column_count = 0 THEN
        ALTER TABLE supplier ADD COLUMN updated_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '最后维护人姓名' AFTER updated_by_id;
    END IF;

    SELECT COUNT(1) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'updated_by_id';
    IF column_count = 0 THEN
        ALTER TABLE supplier_product ADD COLUMN updated_by_id BIGINT DEFAULT NULL COMMENT '最后维护人ID' AFTER update_time;
    END IF;

    SELECT COUNT(1) INTO column_count
      FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_product' AND column_name = 'updated_by_name';
    IF column_count = 0 THEN
        ALTER TABLE supplier_product ADD COLUMN updated_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '最后维护人姓名' AFTER updated_by_id;
    END IF;
END$$

CALL migrate_purchase_detail_audit_fields()$$
DROP PROCEDURE migrate_purchase_detail_audit_fields$$

DELIMITER ;
