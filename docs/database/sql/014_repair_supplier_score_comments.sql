-- 修复开发库曾以非 UTF-8 客户端执行迁移后产生的字段注释乱码。
-- 执行客户端必须显式指定 UTF-8：
-- mysql --default-character-set=utf8mb4 -h 127.0.0.1 -P 13307 -uroot -p erp < 014_repair_supplier_score_comments.sql

ALTER TABLE supplier
    MODIFY COLUMN score_basis_amount BIGINT DEFAULT NULL COMMENT '近180天已确认样本金额（分），不足样本时为空',
    MODIFY COLUMN score_status VARCHAR(16) NOT NULL DEFAULT 'NOT_READY' COMMENT '评分状态：NOT_READY 样本不足；READY 可参与评分';

ALTER TABLE supplier_product
    MODIFY COLUMN score_basis_amount BIGINT DEFAULT NULL COMMENT '近180天已确认样本金额（分），不足样本时为空',
    MODIFY COLUMN score_status VARCHAR(16) NOT NULL DEFAULT 'NOT_READY' COMMENT '评分状态：NOT_READY 样本不足；READY 可参与评分';

ALTER TABLE purchase_order
    MODIFY COLUMN fully_received_at DATETIME DEFAULT NULL COMMENT '使整张采购单完全入库的最后一笔入库确认时间',
    MODIFY COLUMN cancelled_at DATETIME DEFAULT NULL COMMENT '取消时间',
    MODIFY COLUMN cancel_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '取消原因',
    MODIFY COLUMN cancel_affects_delivery_score TINYINT NOT NULL DEFAULT 0 COMMENT '是否计入供应商交付扣分：供应商责任为1，非供应商责任为0',
    MODIFY COLUMN cancelled_by_id BIGINT DEFAULT NULL COMMENT '取消人ID',
    MODIFY COLUMN cancelled_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '取消人名称';

ALTER TABLE supplier_score_change_log
    COMMENT = '供应商评分变化日志',
    MODIFY COLUMN id BIGINT NOT NULL COMMENT '评分变化日志ID',
    MODIFY COLUMN change_key CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '幂等键',
    MODIFY COLUMN batch_no VARCHAR(64) NOT NULL COMMENT '重算批次号；人工操作也必须生成批次号',
    MODIFY COLUMN rule_version VARCHAR(32) NOT NULL COMMENT '评分规则版本',
    MODIFY COLUMN supplier_id BIGINT NOT NULL COMMENT '供应商ID',
    MODIFY COLUMN supplier_product_id BIGINT DEFAULT NULL COMMENT '供应商供货产品ID；供应商维度指标为空',
    MODIFY COLUMN metric_type VARCHAR(16) NOT NULL COMMENT '指标类型：PRICE、QUALITY、DELIVERY、SERVICE',
    MODIFY COLUMN metric_score_before INT DEFAULT NULL COMMENT '指标变更前评分，INT×100',
    MODIFY COLUMN metric_score_after INT DEFAULT NULL COMMENT '指标变更后评分，INT×100',
    MODIFY COLUMN product_recommend_score_before INT DEFAULT NULL COMMENT '产品推荐分变更前，INT×100',
    MODIFY COLUMN product_recommend_score_after INT DEFAULT NULL COMMENT '产品推荐分变更后，INT×100',
    MODIFY COLUMN supplier_overall_score_before INT DEFAULT NULL COMMENT '供应商综合分变更前，INT×100',
    MODIFY COLUMN supplier_overall_score_after INT DEFAULT NULL COMMENT '供应商综合分变更后，INT×100',
    MODIFY COLUMN trigger_type VARCHAR(32) NOT NULL COMMENT '触发来源：PRICE_TRIGGER、SERVICE_TRIGGER、INBOUND_TRIGGER、QUOTE_EXPIRED_TRIGGER、DAILY_TRIGGER、MERGED',
    MODIFY COLUMN related_sources JSON NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '完整来源数组：businessType、字符串businessId、同对象businessNo；无来源为空数组',
    MODIFY COLUMN operator_id BIGINT DEFAULT NULL COMMENT '操作人ID；系统任务为空',
    MODIFY COLUMN operator_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '操作人名称；USER填真实姓名，SYSTEM填明确场景',
    MODIFY COLUMN reason VARCHAR(600) NOT NULL DEFAULT '' COMMENT '人工调整原因或系统计算说明；请求原因最多500字，额外保留校正说明',
    MODIFY COLUMN create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
