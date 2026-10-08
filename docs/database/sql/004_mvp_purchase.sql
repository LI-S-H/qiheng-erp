-- MVP 供应商采购库表设计：极简版
-- 数据库：MySQL 8
-- 说明：主键由 MyBatis-Plus ASSIGN_ID 生成，因此不使用 AUTO_INCREMENT。

USE erp;

CREATE TABLE IF NOT EXISTS supplier (
    id BIGINT NOT NULL COMMENT '供应商ID',
    supplier_code VARCHAR(64) NOT NULL COMMENT '供应商编码',
    supplier_name VARCHAR(200) NOT NULL COMMENT '供应商名称',
    contact_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '联系人（业务可选，未维护时为空字符串）',
    contact_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '联系电话（业务可选，未维护时为空字符串）',
    address VARCHAR(255) NOT NULL DEFAULT '' COMMENT '地址（业务可选，未维护时为空字符串）',
    payment_terms VARCHAR(100) NOT NULL DEFAULT '' COMMENT '付款条件（业务可选，未维护时为空字符串）',
    overall_score INT NULL DEFAULT NULL COMMENT '供应商综合评分，INT×100，NULL表示样本不足',
    delivery_score INT NULL DEFAULT NULL COMMENT '供应商交付评分，INT×100，NULL表示样本不足',
    quality_score INT NULL DEFAULT NULL COMMENT '供应商质量评分，INT×100，NULL表示样本不足',
    price_score INT NULL DEFAULT NULL COMMENT '供应商价格评分，INT×100，NULL表示样本不足',
    service_score INT NULL DEFAULT NULL COMMENT '人工服务评分，INT×100，NULL表示尚未人工设定',
    service_score_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '当前人工服务评分原因；服务分为空时为空字符串',
    avg_delivery_days DECIMAL(10,2) NULL DEFAULT NULL COMMENT '最近180天完全入库订单的金额加权平均到货周期（天）',
    score_basis_amount BIGINT NOT NULL DEFAULT 0 COMMENT '已确认入库累计金额，单位分；不参与质量分计算',
    score_status VARCHAR(16) NOT NULL DEFAULT 'NOT_READY' COMMENT '评分状态：NOT_READY样本不足、READY可参与推荐',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    updated_by_id BIGINT DEFAULT NULL COMMENT '最后维护人ID',
    updated_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '最后维护人姓名',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_code (supplier_code),
    KEY idx_supplier_name (supplier_name),
    KEY idx_supplier_score (overall_score),
    KEY idx_supplier_deleted_status (deleted, status),
    KEY idx_supplier_score_ready (deleted, status, score_status, overall_score DESC),
    CONSTRAINT chk_supplier_score_range CHECK (
        (overall_score IS NULL OR overall_score BETWEEN 0 AND 10000)
        AND (delivery_score IS NULL OR delivery_score BETWEEN 0 AND 10000)
        AND (quality_score IS NULL OR quality_score BETWEEN 0 AND 10000)
        AND (price_score IS NULL OR price_score BETWEEN 0 AND 10000)
        AND (service_score IS NULL OR service_score BETWEEN 0 AND 10000)
    ),
    CONSTRAINT chk_supplier_score_basis_amount CHECK (score_basis_amount >= 0),
    CONSTRAINT chk_supplier_score_status CHECK (score_status IN ('NOT_READY', 'READY')),
    CONSTRAINT chk_supplier_service_score_reason CHECK (
        (service_score IS NULL AND service_score_reason = '')
        OR (service_score IS NOT NULL AND CHAR_LENGTH(TRIM(service_score_reason)) > 0)
    ),
    CONSTRAINT chk_supplier_score_direct_cutover_complete CHECK (1 = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商表';

CREATE TABLE IF NOT EXISTS supplier_product (
    id BIGINT NOT NULL COMMENT '供应商供货产品ID',
    supplier_id BIGINT NOT NULL COMMENT '供应商ID',
    product_id BIGINT NOT NULL COMMENT '产品ID',
    latest_purchase_price BIGINT NULL DEFAULT NULL COMMENT '由已确认入库事实维护的最近成交单价，单位分',
    quoted_purchase_price BIGINT NULL DEFAULT NULL COMMENT '人工有效报价，单位分',
    quoted_price_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '当前人工有效报价原因；报价为空时为空字符串',
    quoted_price_updated_at DATETIME NULL DEFAULT NULL COMMENT '报价最近维护时间',
    quote_valid_until DATE NULL DEFAULT NULL COMMENT '报价有效截止日',
    min_order_qty BIGINT NOT NULL DEFAULT 0 COMMENT '最小起订量，放大100倍保存，1000表示10.00',
    quality_score INT NULL DEFAULT NULL COMMENT '供应产品质量评分，INT×100，NULL表示样本不足',
    price_score INT NULL DEFAULT NULL COMMENT '供应产品价格评分，INT×100，NULL表示样本不足',
    recommend_score INT NULL DEFAULT NULL COMMENT '供应产品推荐评分，INT×100，NULL表示样本不足',
    last_purchase_at DATETIME DEFAULT NULL COMMENT '当前最近成交价对应的最后一次已确认采购入库时间',
    avg_delivery_days DECIMAL(10,2) NULL DEFAULT NULL COMMENT '最近180天完全入库采购单的金额加权平均到货周期（天），仅供分析',
    score_basis_amount BIGINT NOT NULL DEFAULT 0 COMMENT '已确认入库累计金额，单位分；不参与质量分计算',
    score_status VARCHAR(16) NOT NULL DEFAULT 'NOT_READY' COMMENT '评分状态：NOT_READY样本不足、READY可参与推荐',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    updated_by_id BIGINT DEFAULT NULL COMMENT '最后维护人ID',
    updated_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '最后维护人姓名',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_product (supplier_id, product_id),
    KEY idx_supplier_product_product (product_id),
    KEY idx_supplier_product_deleted_status (deleted, status),
    KEY idx_supplier_product_ready (product_id, deleted, status, score_status, recommend_score DESC),
    KEY idx_supplier_product_quote_valid (product_id, deleted, status, quote_valid_until),
    CONSTRAINT chk_supplier_product_score_range CHECK (
        (quality_score IS NULL OR quality_score BETWEEN 0 AND 10000)
        AND (price_score IS NULL OR price_score BETWEEN 0 AND 10000)
        AND (recommend_score IS NULL OR recommend_score BETWEEN 0 AND 10000)
    ),
    CONSTRAINT chk_supplier_product_score_basis_amount CHECK (score_basis_amount >= 0),
    CONSTRAINT chk_supplier_product_score_status CHECK (score_status IN ('NOT_READY', 'READY')),
    CONSTRAINT chk_supplier_product_quote CHECK (
        (quoted_purchase_price IS NULL AND quoted_price_reason = '' AND quoted_price_updated_at IS NULL AND quote_valid_until IS NULL)
        OR (quoted_purchase_price > 0 AND CHAR_LENGTH(TRIM(quoted_price_reason)) > 0 AND quoted_price_updated_at IS NOT NULL AND quote_valid_until IS NOT NULL)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商供货产品表';

CREATE TABLE IF NOT EXISTS purchase_order (
    id BIGINT NOT NULL COMMENT '采购订单ID',
    purchase_no VARCHAR(64) NOT NULL COMMENT '采购单号',
    supplier_id BIGINT NOT NULL COMMENT '供应商ID',
    supplier_code VARCHAR(64) NOT NULL COMMENT '供应商编码冗余',
    supplier_name VARCHAR(200) NOT NULL COMMENT '供应商名称冗余',
    warehouse_id BIGINT NOT NULL COMMENT '目标入库仓库ID',
    warehouse_name VARCHAR(100) NOT NULL COMMENT '目标入库仓库名称冗余',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT、SUBMITTED、APPROVED、PARTIAL_INBOUND、INBOUND_DONE、CANCELLED',
    total_amount BIGINT NOT NULL DEFAULT 0 COMMENT '订单总金额，放大100倍保存，84480表示844.80',
    expected_arrival_date DATE DEFAULT NULL COMMENT '预计到货日期，提交和审核前必须非空',
    created_by_id BIGINT DEFAULT NULL COMMENT '创建人ID',
    created_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '创建人姓名',
    submitted_at DATETIME DEFAULT NULL COMMENT '提交时间',
    submitted_by_id BIGINT DEFAULT NULL COMMENT '提交人ID',
    submitted_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '提交人姓名',
    approved_by_id BIGINT DEFAULT NULL COMMENT '审核人ID',
    approved_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '审核人姓名',
    approved_at DATETIME DEFAULT NULL COMMENT '审核时间',
    fully_received_at DATETIME NULL DEFAULT NULL COMMENT '使整张采购单完全入库的最后一笔入库确认时间',
    cancelled_at DATETIME NULL DEFAULT NULL COMMENT '预留取消时间，当前取消接口未写入',
    cancel_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '取消原因',
    cancel_affects_delivery_score TINYINT NOT NULL DEFAULT 0 COMMENT '预留供应商责任取消标记，当前接口和评分未接入',
    cancelled_by_id BIGINT NULL DEFAULT NULL COMMENT '取消操作人ID',
    cancelled_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '取消操作人名称',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_purchase_order_no (purchase_no),
    KEY idx_purchase_order_supplier (supplier_id),
    KEY idx_purchase_order_warehouse (warehouse_id),
    KEY idx_purchase_order_status (status),
    KEY idx_purchase_order_create_time (create_time),
    KEY idx_purchase_order_deleted_status (deleted, status),
    KEY idx_purchase_order_supplier_due (supplier_id, deleted, expected_arrival_date, status),
    KEY idx_purchase_order_supplier_received (supplier_id, deleted, status, fully_received_at),
    CONSTRAINT chk_purchase_order_cancel_affects_score CHECK (cancel_affects_delivery_score IN (0, 1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购订单主表';

CREATE TABLE IF NOT EXISTS purchase_order_item (
    id BIGINT NOT NULL COMMENT '明细ID',
    purchase_order_id BIGINT NOT NULL COMMENT '采购订单ID',
    purchase_no VARCHAR(64) NOT NULL COMMENT '采购单号冗余',
    supplier_product_id BIGINT DEFAULT NULL COMMENT '供应商供货产品ID',
    product_id BIGINT NOT NULL COMMENT '产品ID',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码冗余',
    product_name VARCHAR(200) NOT NULL COMMENT '产品名称冗余',
    unit_name VARCHAR(32) NOT NULL DEFAULT '件' COMMENT '单位名称冗余',
    quantity_precision TINYINT NOT NULL DEFAULT 0 COMMENT '数量小数位快照：0-2，下单时从 product.quantity_precision 固化',
    quantity BIGINT NOT NULL DEFAULT 0 COMMENT '采购数量，放大100倍保存，2400表示24.00',
    inbound_qty BIGINT NOT NULL DEFAULT 0 COMMENT '已入库数量，放大100倍保存，900表示9.00',
    unit_price BIGINT NOT NULL DEFAULT 0 COMMENT '采购单价，放大100倍保存，3520表示35.20',
    total_amount BIGINT NOT NULL DEFAULT 0 COMMENT '明细金额，放大100倍保存，168960表示1689.60',
    selected_supplier_score INT NULL DEFAULT NULL COMMENT '审核时由后端冻结的供应产品推荐分快照，INT×100，NULL表示当时不可计算',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    KEY idx_purchase_order_item_order (purchase_order_id),
    KEY idx_purchase_order_item_product (product_id),
    KEY idx_purchase_order_item_supplier_product (supplier_product_id),
    CONSTRAINT chk_purchase_order_item_quantity_precision CHECK (quantity_precision BETWEEN 0 AND 2),
    CONSTRAINT chk_purchase_order_item_selected_supplier_score CHECK (
        selected_supplier_score IS NULL OR selected_supplier_score BETWEEN 0 AND 10000
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购订单明细表';

CREATE TABLE IF NOT EXISTS supplier_score_change_log (
    id BIGINT NOT NULL COMMENT '评分变化日志ID',
    change_key CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '幂等键',
    batch_no VARCHAR(64) NOT NULL COMMENT '重算批次号；人工操作也必须生成批次号',
    rule_version VARCHAR(32) NOT NULL COMMENT '评分规则版本',
    supplier_id BIGINT NOT NULL COMMENT '供应商ID',
    supplier_product_id BIGINT DEFAULT NULL COMMENT '供应商供货产品ID；供应商维度指标为空',
    metric_type VARCHAR(16) NOT NULL COMMENT '指标类型：PRICE、QUALITY、DELIVERY、SERVICE',
    metric_score_before INT DEFAULT NULL COMMENT '指标变更前评分，INT×100',
    metric_score_after INT DEFAULT NULL COMMENT '指标变更后评分，INT×100',
    product_recommend_score_before INT DEFAULT NULL COMMENT '产品推荐分变更前，INT×100',
    product_recommend_score_after INT DEFAULT NULL COMMENT '产品推荐分变更后，INT×100',
    supplier_overall_score_before INT DEFAULT NULL COMMENT '供应商综合分变更前，INT×100',
    supplier_overall_score_after INT DEFAULT NULL COMMENT '供应商综合分变更后，INT×100',
    trigger_type VARCHAR(32) NOT NULL COMMENT '触发来源：PRICE_TRIGGER、SERVICE_TRIGGER、INBOUND_TRIGGER、QUOTE_EXPIRED_TRIGGER、DAILY_TRIGGER、MERGED',
    related_sources JSON NOT NULL DEFAULT (JSON_ARRAY()) COMMENT '完整来源数组：businessType、字符串businessId、同对象businessNo；无来源为空数组',
    operator_type VARCHAR(16) NOT NULL DEFAULT 'USER' COMMENT '操作人类型:USER-人工操作,SYSTEM-系统任务(如 MQ Consumer、D1/D2 定时任务、合并重算)',
    operator_id BIGINT DEFAULT NULL COMMENT '操作人ID;SYSTEM 类型时为 NULL',
    operator_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '操作人名称;USER 类型填姓名,SYSTEM 类型填场景描述(如 D2-每日兜底)',
    reason VARCHAR(600) NOT NULL DEFAULT '' COMMENT '人工调整原因或系统计算说明；请求原因最多500字，额外保留校正说明',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_score_change_key (change_key),
    KEY idx_supplier_score_log_supplier_time (supplier_id, create_time DESC),
    KEY idx_supplier_score_log_product_time (supplier_product_id, create_time DESC),
    KEY idx_supplier_score_log_time (create_time DESC, id DESC),
    KEY idx_supplier_score_log_batch (batch_no),
    KEY idx_supplier_score_log_operator_type (operator_type, create_time DESC),
    CONSTRAINT chk_supplier_score_log_metric_type CHECK (metric_type IN ('PRICE', 'QUALITY', 'DELIVERY', 'SERVICE')),
    CONSTRAINT chk_supplier_score_log_operator_type CHECK (operator_type IN ('USER', 'SYSTEM')),
    CONSTRAINT chk_supplier_score_log_sources CHECK (JSON_TYPE(related_sources) = 'ARRAY'),
    CONSTRAINT chk_supplier_score_log_trigger_type CHECK (trigger_type IN ('PRICE_TRIGGER', 'SERVICE_TRIGGER', 'INBOUND_TRIGGER', 'QUOTE_EXPIRED_TRIGGER', 'DAILY_TRIGGER', 'MERGED')),
    CONSTRAINT chk_supplier_score_log_operator_identity CHECK (
        CHAR_LENGTH(TRIM(operator_name)) > 0
        AND ((operator_type = 'USER' AND operator_id IS NOT NULL) OR (operator_type = 'SYSTEM' AND operator_id IS NULL))
    ),
    CONSTRAINT chk_supplier_score_log_score_range CHECK (
        (metric_score_before IS NULL OR metric_score_before BETWEEN 0 AND 10000)
        AND (metric_score_after IS NULL OR metric_score_after BETWEEN 0 AND 10000)
        AND (product_recommend_score_before IS NULL OR product_recommend_score_before BETWEEN 0 AND 10000)
        AND (product_recommend_score_after IS NULL OR product_recommend_score_after BETWEEN 0 AND 10000)
        AND (supplier_overall_score_before IS NULL OR supplier_overall_score_before BETWEEN 0 AND 10000)
        AND (supplier_overall_score_after IS NULL OR supplier_overall_score_after BETWEEN 0 AND 10000)
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商评分变化日志';

-- ============================================================
-- 采购模块种子数据：产品、仓库和用户均引用 001/002/003 的既有种子。
-- 201 号段由本文件专用；历史采购单与仓库工作单、库存流水按来源单号关联。
-- ============================================================

INSERT INTO supplier (
    id, supplier_code, supplier_name, contact_name, contact_phone, address, payment_terms,
    status, create_time, update_time, deleted, remark, version
) VALUES
(2010000000000000001, 'S001', '华东饮品供应链', '陆明', '021-6628-1001', '上海市嘉定区安亭镇', '月结30天', 1, '2026-06-02 09:10:00', '2026-06-12 15:30:00', 0, '常用供应商，可用于采购建议候选', 0),
(2010000000000000002, 'S002', '晨岛咖啡贸易', '林璇', '0571-6628-1002', '杭州市钱塘区', '预付30% 到货结清', 1, '2026-06-03 09:10:00', '2026-06-13 15:30:00', 0, '常用供应商，可用于采购建议候选', 0),
(2010000000000000003, 'S003', '谷仓食品批发', '周可', '025-6628-1003', '南京市江宁区', '月结45天', 1, '2026-06-04 09:10:00', '2026-06-14 15:30:00', 0, '常用供应商，可用于采购建议候选', 0),
(2010000000000000004, 'S004', '文仪办公渠道', '朱婧', '020-6628-1004', '广州市天河区', '月结30天', 1, '2026-06-05 09:10:00', '2026-06-15 15:30:00', 0, '', 0),
(2010000000000000005, 'S005', '森纸纸业集团', '宋元', '0512-6628-1005', '苏州市工业园区', '月结60天', 1, '2026-06-06 09:10:00', '2026-06-12 15:30:00', 0, '', 0),
(2010000000000000006, 'S006', '拓联数码配件', '陈意', '0755-6628-1006', '深圳市龙华区', '现款现货', 0, '2026-06-07 09:10:00', '2026-06-13 15:30:00', 0, '', 0)
ON DUPLICATE KEY UPDATE supplier_code = VALUES(supplier_code), supplier_name = VALUES(supplier_name),
contact_name = VALUES(contact_name), contact_phone = VALUES(contact_phone), address = VALUES(address), payment_terms = VALUES(payment_terms),
status = VALUES(status),
create_time = VALUES(create_time), update_time = VALUES(update_time), deleted = VALUES(deleted), remark = VALUES(remark), version = VALUES(version);

INSERT INTO supplier_product (
    id, supplier_id, product_id,
    quoted_purchase_price, quoted_price_reason, quoted_price_updated_at, quote_valid_until,
    min_order_qty, status,
    create_time, update_time, deleted, remark, version
) VALUES
(2011000000000000001, 2010000000000000001, 1920000000000000001, 3520, '开发库初始报价，待业务确认', '2026-08-15 16:00:00', '2026-12-31', 1000, 1, '2026-06-03 10:00:00', '2026-06-12 16:10:00', 0, '采购建议候选，评分待后续计算', 0),
(2011000000000000002, 2010000000000000002, 1920000000000000002, 4050, '开发库初始报价，待业务确认', '2026-08-15 16:00:00', '2026-12-31', 800, 1, '2026-06-04 10:00:00', '2026-06-13 16:10:00', 0, '采购建议候选，评分待后续计算', 0),
(2011000000000000003, 2010000000000000003, 1920000000000000007, 6800, '开发库初始报价，待业务确认', '2026-08-15 16:00:00', '2026-12-31', 600, 1, '2026-06-05 10:00:00', '2026-06-14 16:10:00', 0, '采购建议候选，评分待后续计算', 0),
(2011000000000000004, 2010000000000000003, 1920000000000000008, 5800, '开发库初始报价，待业务确认', '2026-08-15 16:00:00', '2026-12-31', 500, 1, '2026-06-06 10:00:00', '2026-06-15 16:10:00', 0, '', 0),
(2011000000000000005, 2010000000000000004, 1920000000000000021, 1250, '开发库初始报价，待业务确认', '2026-08-15 16:00:00', '2026-12-31', 2000, 1, '2026-06-07 10:00:00', '2026-06-12 16:10:00', 0, '', 0),
(2011000000000000006, 2010000000000000005, 1920000000000000026, 9200, '开发库初始报价，待业务确认', '2026-08-15 16:00:00', '2026-12-31', 1200, 1, '2026-06-08 10:00:00', '2026-06-13 16:10:00', 0, '', 0),
(2011000000000000007, 2010000000000000006, 1920000000000000043, NULL, '', NULL, NULL, 200, 0, '2026-06-09 10:00:00', '2026-06-14 16:10:00', 0, '', 0),
(2011000000000000008, 2010000000000000001, 1920000000000000002, NULL, '', NULL, NULL, 800, 0, '2026-06-01 10:00:00', '2026-06-14 09:12:00', 0, '历史采购来源映射，仅用于已发生单据追溯', 0),
(2011000000000000009, 2010000000000000003, 1920000000000000033, NULL, '', NULL, NULL, 600, 0, '2026-06-01 10:00:00', '2026-06-13 15:28:00', 0, '历史采购来源映射，仅用于已发生单据追溯', 0),
(2011000000000000010, 2010000000000000004, 1920000000000000038, NULL, '', NULL, NULL, 2000, 0, '2026-06-01 10:00:00', '2026-06-12 17:36:00', 0, '', 0),
(2011000000000000101, 2010000000000000005, 1920000000000000022, NULL, '', NULL, NULL, 700, 0, '2026-06-01 10:00:00', '2026-06-12 14:45:00', 0, '历史采购退回来源映射，仅用于已发生单据追溯', 0)
ON DUPLICATE KEY UPDATE supplier_id = VALUES(supplier_id), product_id = VALUES(product_id),
quoted_purchase_price = VALUES(quoted_purchase_price), quoted_price_reason = VALUES(quoted_price_reason),
quoted_price_updated_at = VALUES(quoted_price_updated_at), quote_valid_until = VALUES(quote_valid_until),
min_order_qty = VALUES(min_order_qty),
status = VALUES(status), create_time = VALUES(create_time), update_time = VALUES(update_time),
deleted = VALUES(deleted), remark = VALUES(remark), version = VALUES(version);
INSERT INTO purchase_order (
    id, purchase_no, supplier_id, supplier_code, supplier_name, warehouse_id, warehouse_name,
    status, total_amount, expected_arrival_date, created_by_id, created_by_name,
    submitted_at, approved_by_id, approved_by_name, approved_at, fully_received_at, create_time, update_time, deleted, remark, version
) VALUES
(2012000000000000101, 'PO202606001', 2010000000000000001, 'S001', '华东饮品供应链', 1930000000000000001, '华东中心仓', 'INBOUND_DONE', 249960, '2026-06-14', 1900000000000000002, '采购主管', '2026-06-13 09:10:00', 1900000000000000002, '采购主管', '2026-06-13 10:00:00', '2026-06-14 09:12:00', '2026-06-13 09:10:00', '2026-06-14 09:12:00', 0, '对应 IB202606140001，已确认入库', 0),
(2012000000000000102, 'PO202606002', 2010000000000000003, 'S003', '谷仓食品批发', 1930000000000000003, '华北中心仓', 'APPROVED', 483600, '2026-06-13', 1900000000000000002, '采购主管', '2026-06-12 14:20:00', 1900000000000000002, '采购主管', '2026-06-12 15:00:00', NULL, '2026-06-12 14:20:00', '2026-06-13 15:28:00', 0, '对应 IB202606130006，待确认入库', 0),
(2012000000000000103, 'PO202606003', 2010000000000000004, 'S004', '文仪办公渠道', 1930000000000000005, '武汉中转仓', 'APPROVED', 31200, '2026-06-12', 1900000000000000002, '采购主管', '2026-06-11 16:10:00', 1900000000000000002, '采购主管', '2026-06-11 16:40:00', NULL, '2026-06-11 16:10:00', '2026-06-12 17:36:00', 0, '对应 IB202606120009，待确认入库', 0),
(2012000000000000104, 'PO202606004', 2010000000000000004, 'S004', '文仪办公渠道', 1930000000000000001, '华东中心仓', 'CANCELLED', 25000, '2026-06-11', 1900000000000000002, '采购主管', '2026-06-10 11:00:00', NULL, '', NULL, NULL, '2026-06-10 11:00:00', '2026-06-11 09:00:00', 0, '历史取消采购单，未生成入库工作单', 0),
(2012000000000000105, 'PO202606005', 2010000000000000005, 'S005', '森纸纸业集团', 1930000000000000006, '西安中转仓', 'INBOUND_DONE', 4760, '2026-06-12', 1900000000000000002, '采购主管', '2026-06-11 13:30:00', 1900000000000000002, '采购主管', '2026-06-11 14:00:00', NULL, '2026-06-11 13:30:00', '2026-06-12 14:45:00', 0, '对应 PRO202606002，已完成入库后全量采购退回', 0),
(2012000000000000001, 'PO202607001', 2010000000000000001, 'S001', '华东饮品供应链', 1930000000000000001, '华东中心仓', 'APPROVED', 84480, '2026-07-24', 1900000000000000002, '采购主管', '2026-07-12 10:30:00', 1900000000000000002, '采购主管', '2026-07-13 09:20:00', NULL, '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0),
(2012000000000000002, 'PO202607002', 2010000000000000005, 'S005', '森纸纸业集团', 1930000000000000008, '南京备货仓', 'PARTIAL_INBOUND', 165600, '2026-07-22', 1900000000000000002, '采购主管', '2026-07-12 10:30:00', 1900000000000000002, '采购主管', '2026-07-13 09:20:00', NULL, '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0),
(2012000000000000003, 'PO202607003', 2010000000000000004, 'S004', '文仪办公渠道', 1930000000000000005, '武汉中转仓', 'DRAFT', 37500, '2026-07-28', 1900000000000000001, '系统管理员', NULL, NULL, '', NULL, NULL, '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0),
(2012000000000000004, 'PO202607004', 2010000000000000003, 'S003', '谷仓食品批发', 1930000000000000003, '华北中心仓', 'SUBMITTED', 108800, '2026-07-30', 1900000000000000002, '采购主管', '2026-07-12 10:30:00', NULL, '', NULL, NULL, '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0),
(2012000000000000005, 'PO202607005', 2010000000000000001, 'S001', '华东饮品供应链', 1930000000000000001, '华东中心仓', 'SUBMITTED', 82740, '2026-07-30', 1900000000000000002, '采购主管', '2026-07-12 10:30:00', NULL, '', NULL, NULL, '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0),
(2012000000000000006, 'PO202607006', 2010000000000000004, 'S004', '文仪办公渠道', 1930000000000000005, '武汉中转仓', 'INBOUND_DONE', 25000, '2026-07-20', 1900000000000000002, '采购主管', '2026-07-12 10:30:00', 1900000000000000002, '采购主管', '2026-07-13 09:20:00', NULL, '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0),
(2012000000000000007, 'PO202607007', 2010000000000000002, 'S002', '晨岛咖啡贸易', 1930000000000000001, '华东中心仓', 'CANCELLED', 32400, '2026-07-26', 1900000000000000001, '系统管理员', '2026-07-12 10:30:00', NULL, '', NULL, NULL, '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0)
ON DUPLICATE KEY UPDATE purchase_no = VALUES(purchase_no), supplier_id = VALUES(supplier_id), supplier_code = VALUES(supplier_code), supplier_name = VALUES(supplier_name),
warehouse_id = VALUES(warehouse_id), warehouse_name = VALUES(warehouse_name), status = VALUES(status), total_amount = VALUES(total_amount), expected_arrival_date = VALUES(expected_arrival_date),
created_by_id = VALUES(created_by_id), created_by_name = VALUES(created_by_name), submitted_at = VALUES(submitted_at), approved_by_id = VALUES(approved_by_id), approved_by_name = VALUES(approved_by_name), approved_at = VALUES(approved_at),
fully_received_at = VALUES(fully_received_at),
create_time = VALUES(create_time), update_time = VALUES(update_time), deleted = VALUES(deleted), remark = VALUES(remark), version = VALUES(version);

INSERT INTO purchase_order_item (
    id, purchase_order_id, purchase_no, supplier_product_id, product_id, product_code, product_name, unit_name,
    quantity, inbound_qty, unit_price, total_amount, create_time, update_time, remark
) VALUES
(2012100000000000101, 2012000000000000101, 'PO202606001', 2011000000000000001, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 4800, 4800, 3520, 168960, '2026-06-13 09:10:00', '2026-06-14 09:12:00', '对应 IB202606140001'),
(2012100000000000102, 2012000000000000101, 'PO202606001', 2011000000000000008, 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 2000, 2000, 4050, 81000, '2026-06-13 09:10:00', '2026-06-14 09:12:00', '对应 IB202606140001'),
(2012100000000000103, 2012000000000000102, 'PO202606002', 2011000000000000003, 1920000000000000007, 'P000007', '每日坚果混合装', '盒', 6000, 0, 6800, 408000, '2026-06-12 14:20:00', '2026-06-13 15:28:00', '对应 IB202606130006，待确认'),
(2012100000000000104, 2012000000000000102, 'PO202606002', 2011000000000000009, 1920000000000000033, 'P000033', '浓缩洗衣液', '瓶', 2700, 0, 2800, 75600, '2026-06-12 14:20:00', '2026-06-13 15:28:00', '对应 IB202606130006，待确认'),
(2012100000000000105, 2012000000000000103, 'PO202606003', 2011000000000000010, 1920000000000000038, 'P000038', '无痕粘钩', '卡', 4000, 0, 780, 31200, '2026-06-11 16:10:00', '2026-06-12 17:36:00', '对应 IB202606120009，待确认'),
(2012100000000000106, 2012000000000000104, 'PO202606004', 2011000000000000005, 1920000000000000021, 'P000021', '中性签字笔', '盒', 2000, 0, 1250, 25000, '2026-06-10 11:00:00', '2026-06-11 09:00:00', '历史取消采购单'),
(2012100000000000107, 2012000000000000105, 'PO202606005', 2011000000000000101, 1920000000000000022, 'P000022', '彩色便利贴', '本', 700, 700, 680, 4760, '2026-06-11 13:30:00', '2026-06-12 14:45:00', '对应 PRO202606002，已完成入库后退回 7 本'),
(2012100000000000001, 2012000000000000001, 'PO202607001', 2011000000000000001, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 2400, 0, 3520, 84480, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000002, 2012000000000000002, 'PO202607002', 2011000000000000006, 1920000000000000026, 'P000026', 'A4复印纸', '箱', 1800, 900, 9200, 165600, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000003, 2012000000000000003, 'PO202607003', 2011000000000000005, 1920000000000000021, 'P000021', '中性签字笔', '盒', 3000, 0, 1250, 37500, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000004, 2012000000000000004, 'PO202607004', 2011000000000000003, 1920000000000000007, 'P000007', '每日坚果混合装', '盒', 1600, 0, 6800, 108800, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000005, 2012000000000000005, 'PO202607005', 2011000000000000001, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 1200, 0, 3520, 42240, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000006, 2012000000000000005, 'PO202607005', 2011000000000000008, 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 1000, 0, 4050, 40500, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000007, 2012000000000000006, 'PO202607006', 2011000000000000005, 1920000000000000021, 'P000021', '中性签字笔', '盒', 2000, 2000, 1250, 25000, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000008, 2012000000000000007, 'PO202607007', 2011000000000000002, 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 800, 0, 4050, 32400, '2026-07-12 10:30:00', '2026-07-12 10:30:00', '')
ON DUPLICATE KEY UPDATE purchase_order_id = VALUES(purchase_order_id), purchase_no = VALUES(purchase_no), supplier_product_id = VALUES(supplier_product_id),
product_id = VALUES(product_id), product_code = VALUES(product_code), product_name = VALUES(product_name), unit_name = VALUES(unit_name), quantity = VALUES(quantity),
inbound_qty = VALUES(inbound_qty), unit_price = VALUES(unit_price), total_amount = VALUES(total_amount),
create_time = VALUES(create_time), update_time = VALUES(update_time), remark = VALUES(remark);

-- 003 的仓库种子先于采购明细创建；仅回填以下四张明确的采购种子，不修改真实业务单据。
UPDATE inbound_bill inbound_bill
INNER JOIN purchase_order purchase_order
    ON purchase_order.purchase_no = inbound_bill.source_no
   AND purchase_order.deleted = 0
   SET inbound_bill.source_id = purchase_order.id
 WHERE inbound_bill.inbound_type = 'PURCHASE_IN'
   AND inbound_bill.id IN (1950000000000000001, 1950000000000000006, 1950000000000000009, 1950000000000000014)
   AND inbound_bill.source_id IS NULL;

-- 采购入库明细必须能唯一回溯到采购单明细；先校验再回填，禁止按不确定记录静默写入。
DELIMITER $$
DROP PROCEDURE IF EXISTS preflight_purchase_score_source$$
CREATE PROCEDURE preflight_purchase_score_source()
BEGIN
    DECLARE ambiguous_source_count BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO ambiguous_source_count
      FROM (
            SELECT inbound_bill_item.id
              FROM inbound_bill_item inbound_bill_item
              INNER JOIN inbound_bill inbound_bill
                      ON inbound_bill.id = inbound_bill_item.inbound_bill_id
              LEFT JOIN purchase_order_item purchase_order_item
                     ON purchase_order_item.purchase_order_id = inbound_bill.source_id
                    AND purchase_order_item.product_id = inbound_bill_item.product_id
             WHERE inbound_bill.inbound_type = 'PURCHASE_IN'
               AND inbound_bill.id IN (1950000000000000001, 1950000000000000006, 1950000000000000009, 1950000000000000014)
               AND inbound_bill.source_id IS NOT NULL
               AND inbound_bill_item.source_item_id IS NULL
             GROUP BY inbound_bill_item.id
            HAVING COUNT(purchase_order_item.id) <> 1
      ) AS ambiguous_inbound_item;
    IF ambiguous_source_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '采购入库明细无法唯一映射到采购单明细，初始化终止';
    END IF;

    SELECT COUNT(*) INTO ambiguous_source_count
      FROM (
            SELECT stock_bill_item.id
              FROM stock_bill_item stock_bill_item
              INNER JOIN stock_bill stock_bill
                      ON stock_bill.id = stock_bill_item.bill_id
              LEFT JOIN purchase_order_item purchase_order_item
                     ON purchase_order_item.purchase_order_id = stock_bill.business_source_id
                    AND purchase_order_item.product_id = stock_bill_item.product_id
             WHERE stock_bill.bill_type = 'PURCHASE_IN'
               AND stock_bill.id IN (1990000000000000001)
               AND stock_bill.business_source_id IS NOT NULL
               AND stock_bill_item.business_source_item_id IS NULL
             GROUP BY stock_bill_item.id
            HAVING COUNT(purchase_order_item.id) <> 1
      ) AS ambiguous_stock_item;
    IF ambiguous_source_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '采购入库流水无法唯一映射到采购单明细，初始化终止';
    END IF;
END$$
CALL preflight_purchase_score_source()$$
DROP PROCEDURE preflight_purchase_score_source$$
DELIMITER ;

UPDATE inbound_bill_item inbound_bill_item
INNER JOIN inbound_bill inbound_bill
    ON inbound_bill.id = inbound_bill_item.inbound_bill_id
INNER JOIN purchase_order_item purchase_order_item
    ON purchase_order_item.purchase_order_id = inbound_bill.source_id
   AND purchase_order_item.product_id = inbound_bill_item.product_id
   SET inbound_bill_item.source_item_id = purchase_order_item.id
 WHERE inbound_bill.inbound_type = 'PURCHASE_IN'
   AND inbound_bill.id IN (1950000000000000001, 1950000000000000006, 1950000000000000009, 1950000000000000014)
   AND inbound_bill.source_id IS NOT NULL
   AND inbound_bill_item.source_item_id IS NULL;

UPDATE stock_bill_item stock_bill_item
INNER JOIN stock_bill stock_bill
    ON stock_bill.id = stock_bill_item.bill_id
INNER JOIN purchase_order_item purchase_order_item
    ON purchase_order_item.purchase_order_id = stock_bill.business_source_id
   AND purchase_order_item.product_id = stock_bill_item.product_id
   SET stock_bill_item.business_source_item_id = purchase_order_item.id
 WHERE stock_bill.bill_type = 'PURCHASE_IN'
   AND stock_bill.id IN (1990000000000000001)
   AND stock_bill.business_source_id IS NOT NULL
   AND stock_bill_item.business_source_item_id IS NULL;

-- 种子来源唯一映射后补齐单价与交期快照；只写种子缺失值，不覆盖已保存的真实历史快照。
UPDATE inbound_bill_item inbound_bill_item
INNER JOIN inbound_bill inbound_bill ON inbound_bill.id = inbound_bill_item.inbound_bill_id
INNER JOIN purchase_order_item purchase_order_item
    ON purchase_order_item.id = inbound_bill_item.source_item_id
   AND purchase_order_item.purchase_order_id = inbound_bill.source_id
   AND purchase_order_item.product_id = inbound_bill_item.product_id
   SET inbound_bill_item.unit_price = purchase_order_item.unit_price
 WHERE inbound_bill.inbound_type = 'PURCHASE_IN'
   AND inbound_bill.id IN (1950000000000000001, 1950000000000000006, 1950000000000000009, 1950000000000000014)
   AND inbound_bill_item.unit_price = 0;

UPDATE inbound_bill inbound_bill
INNER JOIN purchase_order purchase_order ON purchase_order.id = inbound_bill.source_id
   SET inbound_bill.expected_arrival_date = purchase_order.expected_arrival_date
 WHERE inbound_bill.inbound_type = 'PURCHASE_IN'
   AND inbound_bill.id IN (1950000000000000001, 1950000000000000006, 1950000000000000009, 1950000000000000014)
   AND inbound_bill.expected_arrival_date IS NULL;

DELIMITER $$

DROP PROCEDURE IF EXISTS validate_purchase_score_source$$

CREATE PROCEDURE validate_purchase_score_source()
BEGIN
    DECLARE missing_source_count BIGINT DEFAULT 0;
    DECLARE ambiguous_source_count BIGINT DEFAULT 0;

    SELECT COUNT(*) INTO ambiguous_source_count
      FROM (
          SELECT inbound_bill_item.id
            FROM inbound_bill_item inbound_bill_item
            INNER JOIN inbound_bill inbound_bill
                ON inbound_bill.id = inbound_bill_item.inbound_bill_id
            INNER JOIN purchase_order_item purchase_order_item
                ON purchase_order_item.purchase_order_id = inbound_bill.source_id
               AND purchase_order_item.product_id = inbound_bill_item.product_id
           WHERE inbound_bill.inbound_type = 'PURCHASE_IN'
             AND inbound_bill.id IN (1950000000000000001, 1950000000000000006, 1950000000000000009, 1950000000000000014)
             AND inbound_bill.source_id IS NOT NULL
           GROUP BY inbound_bill_item.id
          HAVING COUNT(purchase_order_item.id) <> 1
      ) AS ambiguous_inbound_source;
    IF ambiguous_source_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '采购初始化失败：采购入库明细无法唯一关联采购订单明细';
    END IF;

    SELECT COUNT(*) INTO ambiguous_source_count
      FROM (
          SELECT stock_bill_item.id
            FROM stock_bill_item stock_bill_item
            INNER JOIN stock_bill stock_bill
                ON stock_bill.id = stock_bill_item.bill_id
            INNER JOIN purchase_order_item purchase_order_item
                ON purchase_order_item.purchase_order_id = stock_bill.business_source_id
               AND purchase_order_item.product_id = stock_bill_item.product_id
           WHERE stock_bill.bill_type = 'PURCHASE_IN'
             AND stock_bill.id IN (1990000000000000001)
             AND stock_bill.business_source_id IS NOT NULL
           GROUP BY stock_bill_item.id
          HAVING COUNT(purchase_order_item.id) <> 1
      ) AS ambiguous_stock_source;
    IF ambiguous_source_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '采购初始化失败：采购库存流水明细无法唯一关联采购订单明细';
    END IF;

    SELECT COUNT(*) INTO missing_source_count
      FROM inbound_bill_item inbound_bill_item
      INNER JOIN inbound_bill inbound_bill
        ON inbound_bill.id = inbound_bill_item.inbound_bill_id
      INNER JOIN purchase_order purchase_order
        ON purchase_order.id = inbound_bill.source_id
     WHERE inbound_bill.inbound_type = 'PURCHASE_IN'
       AND inbound_bill.id IN (1950000000000000001, 1950000000000000006, 1950000000000000009, 1950000000000000014)
       AND inbound_bill_item.source_item_id IS NULL;
    IF missing_source_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '采购初始化失败：采购入库明细缺少采购订单明细来源';
    END IF;

    SELECT COUNT(*) INTO missing_source_count
      FROM stock_bill_item stock_bill_item
      INNER JOIN stock_bill stock_bill
        ON stock_bill.id = stock_bill_item.bill_id
      INNER JOIN purchase_order purchase_order
        ON purchase_order.id = stock_bill.business_source_id
     WHERE stock_bill.bill_type = 'PURCHASE_IN'
       AND stock_bill.id IN (1990000000000000001)
       AND stock_bill_item.business_source_item_id IS NULL;
    IF missing_source_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '采购初始化失败：采购库存流水明细缺少采购订单明细来源';
    END IF;

    SELECT COUNT(*) INTO missing_source_count
      FROM inbound_bill_item inbound_bill_item
      INNER JOIN inbound_bill inbound_bill ON inbound_bill.id = inbound_bill_item.inbound_bill_id
     WHERE inbound_bill.inbound_type = 'PURCHASE_IN'
       AND inbound_bill.id IN (1950000000000000001, 1950000000000000006, 1950000000000000009, 1950000000000000014)
       AND (inbound_bill_item.source_item_id IS NULL OR inbound_bill_item.unit_price <= 0
            OR inbound_bill.expected_arrival_date IS NULL);
    IF missing_source_count > 0 THEN
        SIGNAL SQLSTATE '45000'
            SET MESSAGE_TEXT = '采购初始化失败：采购种子缺少有效来源、单价或预计到货快照';
    END IF;
END$$

CALL validate_purchase_score_source()$$
DROP PROCEDURE validate_purchase_score_source$$

DELIMITER ;

-- 本文件仅用于新库结构与开发种子初始化，金额、数量种子已经放大100倍，禁止再次乘100。
-- 已存在库的旧DECIMAL类型转换应使用独立迁移脚本并先核验列类型，不得运行初始化文件代替迁移。
