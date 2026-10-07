-- 开发环境直接切换日志来源结构。执行前必须停止旧版后端并确认日志表为空；有历史日志时先备份并另行迁移，不能直接丢弃来源。
-- 不删除业务记录、不重置编号序列。调用程序必须核对旧字段存在、新字段不存在及 COUNT(*) = 0 后才执行。
ALTER TABLE supplier_score_change_log
    ADD COLUMN related_sources JSON NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '完整来源数组：businessType、字符串businessId、同对象businessNo；无来源为空数组' AFTER trigger_type,
    MODIFY COLUMN reason VARCHAR(600) NOT NULL DEFAULT '' COMMENT '人工调整原因或系统计算说明；请求原因最多500字，额外保留校正说明',
    DROP COLUMN related_business_id,
    DROP COLUMN related_business_no,
    MODIFY COLUMN metric_type VARCHAR(16) NOT NULL COMMENT '指标类型：PRICE、QUALITY、DELIVERY、SERVICE；仅衍生校正在原因中说明',
    MODIFY COLUMN trigger_type VARCHAR(32) NOT NULL COMMENT '触发来源：PRICE_TRIGGER、SERVICE_TRIGGER、INBOUND_TRIGGER、QUOTE_EXPIRED_TRIGGER、DAILY_TRIGGER、MERGED',
    MODIFY COLUMN operator_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '操作人名称：USER 填真实姓名，SYSTEM 填场景说明',
    ADD CONSTRAINT chk_supplier_score_log_sources CHECK (JSON_TYPE(related_sources) = 'ARRAY'),
    ADD CONSTRAINT chk_supplier_score_log_trigger_type CHECK (trigger_type IN ('PRICE_TRIGGER', 'SERVICE_TRIGGER', 'INBOUND_TRIGGER', 'QUOTE_EXPIRED_TRIGGER', 'DAILY_TRIGGER', 'MERGED')),
    ADD CONSTRAINT chk_supplier_score_log_operator_identity CHECK (
        CHAR_LENGTH(TRIM(operator_name)) > 0
        AND ((operator_type = 'USER' AND operator_id IS NOT NULL) OR (operator_type = 'SYSTEM' AND operator_id IS NULL))
    );
