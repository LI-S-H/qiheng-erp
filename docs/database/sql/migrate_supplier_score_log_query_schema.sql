-- 已有库的评分日志查询结构补丁；只补缺失字段与全局分页索引，不改历史日志。
-- 适用于已完成供应商评分直切迁移的 MySQL 8.0+ 数据库。
-- 历史补丁：必须在 migrate_supplier_score_log_sources.sql 之前执行；已完成来源数组改造的库不要重新执行。
USE erp;

DELIMITER $$
DROP PROCEDURE IF EXISTS migrate_supplier_score_log_query_schema$$
CREATE PROCEDURE migrate_supplier_score_log_query_schema()
BEGIN
    DECLARE column_count INT DEFAULT 0;
    DECLARE index_count INT DEFAULT 0;

    SELECT COUNT(*) INTO column_count FROM information_schema.columns
     WHERE table_schema = DATABASE() AND table_name = 'supplier_score_change_log'
       AND column_name = 'operator_type';
    IF column_count = 0 THEN
        ALTER TABLE supplier_score_change_log
            ADD COLUMN operator_type VARCHAR(16) NOT NULL DEFAULT 'USER'
                COMMENT '操作人类型：USER人工操作、SYSTEM系统任务' AFTER related_business_no;
    END IF;

    SELECT COUNT(*) INTO index_count FROM information_schema.statistics
     WHERE table_schema = DATABASE() AND table_name = 'supplier_score_change_log'
       AND index_name = 'idx_supplier_score_log_time';
    IF index_count = 0 THEN
        ALTER TABLE supplier_score_change_log
            ADD KEY idx_supplier_score_log_time (create_time DESC, id DESC);
    END IF;
END$$
CALL migrate_supplier_score_log_query_schema()$$
DROP PROCEDURE migrate_supplier_score_log_query_schema$$
DELIMITER ;
