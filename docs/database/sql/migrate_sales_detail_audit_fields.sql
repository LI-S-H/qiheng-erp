-- 销售详情补充字段迁移：MySQL 8
-- 本脚本只增加字段，不回填历史操作人；历史记录在详情中显示“未记录”。
USE erp;

DELIMITER $$

DROP PROCEDURE IF EXISTS migrate_sales_detail_audit_fields$$
CREATE PROCEDURE migrate_sales_detail_audit_fields()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'customer' AND column_name = 'updated_by_id'
    ) THEN
        ALTER TABLE customer ADD COLUMN updated_by_id BIGINT DEFAULT NULL COMMENT '最后维护人ID' AFTER update_time;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'customer' AND column_name = 'updated_by_name'
    ) THEN
        ALTER TABLE customer ADD COLUMN updated_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '最后维护人姓名' AFTER updated_by_id;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'sales_order' AND column_name = 'submitted_by_id'
    ) THEN
        ALTER TABLE sales_order ADD COLUMN submitted_by_id BIGINT DEFAULT NULL COMMENT '提交人ID' AFTER submitted_at;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = DATABASE() AND table_name = 'sales_order' AND column_name = 'submitted_by_name'
    ) THEN
        ALTER TABLE sales_order ADD COLUMN submitted_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '提交人姓名' AFTER submitted_by_id;
    END IF;
END$$

CALL migrate_sales_detail_audit_fields()$$
DROP PROCEDURE migrate_sales_detail_audit_fields$$

DELIMITER ;
