-- MVP 供应商采购库表设计：极简版
-- 数据库：MySQL 8
-- 说明：主键由 MyBatis-Plus ASSIGN_ID 生成，因此不使用 AUTO_INCREMENT。

USE erp;

CREATE TABLE IF NOT EXISTS supplier (
    id BIGINT NOT NULL COMMENT '供应商ID',
    supplier_code VARCHAR(64) NOT NULL COMMENT '供应商编码',
    supplier_name VARCHAR(200) NOT NULL COMMENT '供应商名称',
    contact_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '联系人',
    contact_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '联系电话',
    address VARCHAR(255) NOT NULL DEFAULT '' COMMENT '地址',
    payment_terms VARCHAR(100) NOT NULL DEFAULT '' COMMENT '付款条件',
    overall_score INT NOT NULL DEFAULT 0 COMMENT '综合评分，放大100倍保存，10000表示100.00',
    delivery_score INT NOT NULL DEFAULT 0 COMMENT '交付评分，放大100倍保存，10000表示100.00',
    quality_score INT NOT NULL DEFAULT 0 COMMENT '质量评分，放大100倍保存，10000表示100.00',
    price_score INT NOT NULL DEFAULT 0 COMMENT '价格评分，放大100倍保存，10000表示100.00',
    service_score INT NOT NULL DEFAULT 0 COMMENT '服务评分，放大100倍保存，10000表示100.00',
    avg_delivery_days DECIMAL(10,2) NOT NULL DEFAULT 0.00 COMMENT '平均交付天数',
    on_time_rate INT NOT NULL DEFAULT 0 COMMENT '准时交付率，放大100倍保存，10000表示100.00%',
    qualified_rate INT NOT NULL DEFAULT 0 COMMENT '到货合格率，放大100倍保存，10000表示100.00%',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_code (supplier_code),
    KEY idx_supplier_name (supplier_name),
    KEY idx_supplier_score (overall_score),
    KEY idx_supplier_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='供应商表';

CREATE TABLE IF NOT EXISTS supplier_product (
    id BIGINT NOT NULL COMMENT '供应商供货产品ID',
    supplier_id BIGINT NOT NULL COMMENT '供应商ID',
    supplier_code VARCHAR(64) NOT NULL COMMENT '供应商编码冗余',
    supplier_name VARCHAR(200) NOT NULL COMMENT '供应商名称冗余',
    product_id BIGINT NOT NULL COMMENT '产品ID',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码冗余',
    product_name VARCHAR(200) NOT NULL COMMENT '产品名称冗余',
    unit_name VARCHAR(32) NOT NULL DEFAULT '件' COMMENT '单位名称冗余',
    supplier_product_code VARCHAR(100) NOT NULL DEFAULT '' COMMENT '供应商侧产品编码',
    latest_purchase_price DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '最近采购单价',
    min_order_qty DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '最小起订量',
    lead_time_days INT NOT NULL DEFAULT 0 COMMENT '预计交期天数',
    delivery_score INT NOT NULL DEFAULT 0 COMMENT '该产品维度交付评分，放大100倍保存，10000表示100.00',
    quality_score INT NOT NULL DEFAULT 0 COMMENT '该产品维度质量评分，放大100倍保存，10000表示100.00',
    price_score INT NOT NULL DEFAULT 0 COMMENT '该产品维度价格评分，放大100倍保存，10000表示100.00',
    ai_score INT NOT NULL DEFAULT 0 COMMENT 'AI/规则综合推荐分，放大100倍保存，10000表示100.00',
    last_purchase_at DATETIME DEFAULT NULL COMMENT '最近采购时间',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_supplier_product (supplier_id, product_id),
    KEY idx_supplier_product_product (product_id),
    KEY idx_supplier_product_score (product_id, ai_score),
    KEY idx_supplier_product_deleted_status (deleted, status)
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
    total_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '订单总金额',
    expected_arrival_date DATE DEFAULT NULL COMMENT '预计到货日期，提交和审核前必须非空',
    created_by_id BIGINT DEFAULT NULL COMMENT '创建人ID',
    created_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '创建人姓名',
    submitted_at DATETIME DEFAULT NULL COMMENT '提交时间',
    approved_by_id BIGINT DEFAULT NULL COMMENT '审核人ID',
    approved_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '审核人姓名',
    approved_at DATETIME DEFAULT NULL COMMENT '审核时间',
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
    KEY idx_purchase_order_deleted_status (deleted, status)
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
    quantity DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '采购数量',
    inbound_qty DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '已入库数量',
    unit_price DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '采购单价',
    total_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '明细金额',
    selected_supplier_score INT NOT NULL DEFAULT 0 COMMENT '下单时供应商推荐分快照，放大100倍保存，10000表示100.00',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_purchase_order_item_order (purchase_order_id),
    KEY idx_purchase_order_item_product (product_id),
    KEY idx_purchase_order_item_supplier_product (supplier_product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='采购订单明细表';

-- ============================================================
-- 采购模块种子数据：产品、仓库和用户均引用 001/002/003 的既有种子。
-- 201 号段由本文件专用；不与仓库模块旧来源单据建立关联。
-- ============================================================

INSERT INTO supplier (
    id, supplier_code, supplier_name, contact_name, contact_phone, address, payment_terms,
    overall_score, delivery_score, quality_score, price_score, service_score, avg_delivery_days,
    on_time_rate, qualified_rate, status, create_time, update_time, deleted, remark, version
) VALUES
(2010000000000000001, 'S001', '华东饮品供应链', '陆明', '021-6628-1001', '上海市嘉定区安亭镇', '月结30天', 9260, 9420, 9610, 8840, 9150, 3.80, 9650, 9820, 1, '2026-06-02 09:10:00', '2026-06-12 15:30:00', 0, '常用供应商，可用于采购建议候选', 0),
(2010000000000000002, 'S002', '晨岛咖啡贸易', '林璇', '0571-6628-1002', '杭州市钱塘区', '预付30% 到货结清', 8830, 8970, 9340, 8420, 8690, 5.20, 9110, 9640, 1, '2026-06-03 09:10:00', '2026-06-13 15:30:00', 0, '常用供应商，可用于采购建议候选', 0),
(2010000000000000003, 'S003', '谷仓食品批发', '周可', '025-6628-1003', '南京市江宁区', '月结45天', 9080, 9240, 9520, 8760, 8930, 4.60, 9480, 9790, 1, '2026-06-04 09:10:00', '2026-06-14 15:30:00', 0, '常用供应商，可用于采购建议候选', 0),
(2010000000000000004, 'S004', '文仪办公渠道', '朱婧', '020-6628-1004', '广州市天河区', '月结30天', 8620, 8540, 9050, 8810, 8360, 6.10, 8870, 9520, 1, '2026-06-05 09:10:00', '2026-06-15 15:30:00', 0, '', 0),
(2010000000000000005, 'S005', '森纸纸业集团', '宋元', '0512-6628-1005', '苏州市工业园区', '月结60天', 9410, 9580, 9720, 9160, 9240, 3.30, 9740, 9890, 1, '2026-06-06 09:10:00', '2026-06-12 15:30:00', 0, '', 0),
(2010000000000000006, 'S006', '拓联数码配件', '陈意', '0755-6628-1006', '深圳市龙华区', '现款现货', 7980, 7820, 8240, 8460, 7580, 8.40, 8210, 9070, 0, '2026-06-07 09:10:00', '2026-06-13 15:30:00', 0, '', 0)
ON DUPLICATE KEY UPDATE supplier_code = VALUES(supplier_code), supplier_name = VALUES(supplier_name),
contact_name = VALUES(contact_name), contact_phone = VALUES(contact_phone), address = VALUES(address), payment_terms = VALUES(payment_terms),
overall_score = VALUES(overall_score), delivery_score = VALUES(delivery_score), quality_score = VALUES(quality_score),
price_score = VALUES(price_score), service_score = VALUES(service_score), avg_delivery_days = VALUES(avg_delivery_days),
on_time_rate = VALUES(on_time_rate), qualified_rate = VALUES(qualified_rate), status = VALUES(status),
create_time = VALUES(create_time), update_time = VALUES(update_time), deleted = VALUES(deleted), remark = VALUES(remark), version = VALUES(version);

INSERT INTO supplier_product (
    id, supplier_id, supplier_code, supplier_name, product_id, product_code, product_name, unit_name,
    supplier_product_code, latest_purchase_price, min_order_qty, lead_time_days,
    delivery_score, quality_score, price_score, ai_score, last_purchase_at, status,
    create_time, update_time, deleted, remark, version
) VALUES
(2011000000000000001, 2010000000000000001, 'S001', '华东饮品供应链', 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 'HD-SD330', 35.20, 10, 3, 9420, 9610, 8840, 9230, '2026-06-13 10:20:00', 1, '2026-06-03 10:00:00', '2026-06-12 16:10:00', 0, '采购建议优先候选', 0),
(2011000000000000002, 2010000000000000002, 'S002', '晨岛咖啡贸易', 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 'CD-CF50', 40.50, 8, 5, 8970, 9340, 8420, 8890, '2026-06-10 11:20:00', 1, '2026-06-04 10:00:00', '2026-06-13 16:10:00', 0, '采购建议优先候选', 0),
(2011000000000000003, 2010000000000000003, 'S003', '谷仓食品批发', 1920000000000000007, 'P000007', '每日坚果混合装', '盒', 'GC-NUT30', 68.00, 6, 4, 9240, 9520, 8760, 9180, '2026-06-11 14:10:00', 1, '2026-06-05 10:00:00', '2026-06-14 16:10:00', 0, '采购建议优先候选', 0),
(2011000000000000004, 2010000000000000003, 'S003', '谷仓食品批发', 1920000000000000008, 'P000008', '海盐苏打饼干', '箱', 'GC-CK06', 58.00, 5, 4, 9240, 9520, 8760, 9140, '2026-06-09 09:40:00', 1, '2026-06-06 10:00:00', '2026-06-15 16:10:00', 0, '', 0),
(2011000000000000005, 2010000000000000004, 'S004', '文仪办公渠道', 1920000000000000021, 'P000021', '中性签字笔', '盒', 'WY-PEN12', 12.50, 20, 6, 8540, 9050, 8810, 8720, NULL, 1, '2026-06-07 10:00:00', '2026-06-12 16:10:00', 0, '', 0),
(2011000000000000006, 2010000000000000005, 'S005', '森纸纸业集团', 1920000000000000026, 'P000026', 'A4复印纸', '箱', 'SZ-A4-70G', 92.00, 12, 3, 9580, 9720, 9160, 9480, '2026-06-12 13:50:00', 1, '2026-06-08 10:00:00', '2026-06-13 16:10:00', 0, '', 0),
(2011000000000000007, 2010000000000000006, 'S006', '拓联数码配件', 1920000000000000043, 'P000043', 'USB-C扩展坞', '个', 'TL-HUB8', 126.00, 2, 8, 7820, 8240, 8460, 8130, NULL, 0, '2026-06-09 10:00:00', '2026-06-14 16:10:00', 0, '', 0),
(2011000000000000008, 2010000000000000001, 'S001', '华东饮品供应链', 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 'HD-CF50-HIS', 40.50, 8, 5, 9420, 9610, 8840, 9140, '2026-06-14 09:12:00', 0, '2026-06-01 10:00:00', '2026-06-14 09:12:00', 0, '历史采购来源映射，仅用于已发生单据追溯', 0),
(2011000000000000009, 2010000000000000003, 'S003', '谷仓食品批发', 1920000000000000033, 'P000033', '浓缩洗衣液', '瓶', 'GC-LD2K-HIS', 28.00, 6, 4, 9240, 9520, 8760, 9010, '2026-06-13 15:28:00', 0, '2026-06-01 10:00:00', '2026-06-13 15:28:00', 0, '历史采购来源映射，仅用于已发生单据追溯', 0),
(2011000000000000010, 2010000000000000004, 'S004', '文仪办公渠道', 1920000000000000038, 'P000038', '无痕粘钩', '卡', 'WY-HOOK6-HIS', 7.80, 20, 6, 8540, 9050, 8810, 8680, '2026-06-12 17:36:00', 0, '2026-06-01 10:00:00', '2026-06-12 17:36:00', 0, '历史采购来源映射，仅用于已发生单据追溯', 0)
ON DUPLICATE KEY UPDATE supplier_id = VALUES(supplier_id), supplier_code = VALUES(supplier_code), supplier_name = VALUES(supplier_name),
product_id = VALUES(product_id), product_code = VALUES(product_code), product_name = VALUES(product_name), unit_name = VALUES(unit_name),
supplier_product_code = VALUES(supplier_product_code), latest_purchase_price = VALUES(latest_purchase_price), min_order_qty = VALUES(min_order_qty), lead_time_days = VALUES(lead_time_days),
delivery_score = VALUES(delivery_score), quality_score = VALUES(quality_score), price_score = VALUES(price_score), ai_score = VALUES(ai_score),
last_purchase_at = VALUES(last_purchase_at), status = VALUES(status), create_time = VALUES(create_time), update_time = VALUES(update_time),
deleted = VALUES(deleted), remark = VALUES(remark), version = VALUES(version);

INSERT INTO purchase_order (
    id, purchase_no, supplier_id, supplier_code, supplier_name, warehouse_id, warehouse_name,
    status, total_amount, expected_arrival_date, created_by_id, created_by_name,
    submitted_at, approved_by_id, approved_by_name, approved_at, create_time, update_time, deleted, remark, version
) VALUES
(2012000000000000101, 'PO202606001', 2010000000000000001, 'S001', '华东饮品供应链', 1930000000000000001, '华东中心仓', 'INBOUND_DONE', 2499.60, '2026-06-14', 1900000000000000002, '采购主管', '2026-06-13 09:10:00', 1900000000000000002, '采购主管', '2026-06-13 10:00:00', '2026-06-13 09:10:00', '2026-06-14 09:12:00', 0, '对应 IB202606140001，已确认入库', 0),
(2012000000000000102, 'PO202606002', 2010000000000000003, 'S003', '谷仓食品批发', 1930000000000000003, '华北中心仓', 'APPROVED', 4836.00, '2026-06-13', 1900000000000000002, '采购主管', '2026-06-12 14:20:00', 1900000000000000002, '采购主管', '2026-06-12 15:00:00', '2026-06-12 14:20:00', '2026-06-13 15:28:00', 0, '对应 IB202606130006，待确认入库', 0),
(2012000000000000103, 'PO202606003', 2010000000000000004, 'S004', '文仪办公渠道', 1930000000000000005, '武汉中转仓', 'APPROVED', 312.00, '2026-06-12', 1900000000000000002, '采购主管', '2026-06-11 16:10:00', 1900000000000000002, '采购主管', '2026-06-11 16:40:00', '2026-06-11 16:10:00', '2026-06-12 17:36:00', 0, '对应 IB202606120009，待确认入库', 0),
(2012000000000000104, 'PO202606004', 2010000000000000004, 'S004', '文仪办公渠道', 1930000000000000001, '华东中心仓', 'CANCELLED', 250.00, '2026-06-11', 1900000000000000002, '采购主管', '2026-06-10 11:00:00', NULL, '', NULL, '2026-06-10 11:00:00', '2026-06-11 09:00:00', 0, '历史取消采购单，未生成入库工作单', 0),
(2012000000000000001, 'PO202607001', 2010000000000000001, 'S001', '华东饮品供应链', 1930000000000000001, '华东中心仓', 'APPROVED', 844.80, '2026-07-24', 1900000000000000002, '采购主管', '2026-07-12 10:30:00', 1900000000000000002, '采购主管', '2026-07-13 09:20:00', '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0),
(2012000000000000002, 'PO202607002', 2010000000000000005, 'S005', '森纸纸业集团', 1930000000000000008, '南京备货仓', 'PARTIAL_INBOUND', 1656.00, '2026-07-22', 1900000000000000002, '采购主管', '2026-07-12 10:30:00', 1900000000000000002, '采购主管', '2026-07-13 09:20:00', '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0),
(2012000000000000003, 'PO202607003', 2010000000000000004, 'S004', '文仪办公渠道', 1930000000000000005, '武汉中转仓', 'DRAFT', 375.00, '2026-07-28', 1900000000000000001, '系统管理员', NULL, NULL, '', NULL, '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0),
(2012000000000000004, 'PO202607004', 2010000000000000003, 'S003', '谷仓食品批发', 1930000000000000003, '华北中心仓', 'SUBMITTED', 1088.00, '2026-07-30', 1900000000000000002, '采购主管', '2026-07-12 10:30:00', NULL, '', NULL, '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0),
(2012000000000000005, 'PO202607005', 2010000000000000001, 'S001', '华东饮品供应链', 1930000000000000001, '华东中心仓', 'SUBMITTED', 827.40, '2026-07-30', 1900000000000000002, '采购主管', '2026-07-12 10:30:00', NULL, '', NULL, '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0),
(2012000000000000006, 'PO202607006', 2010000000000000004, 'S004', '文仪办公渠道', 1930000000000000005, '武汉中转仓', 'INBOUND_DONE', 250.00, '2026-07-20', 1900000000000000002, '采购主管', '2026-07-12 10:30:00', 1900000000000000002, '采购主管', '2026-07-13 09:20:00', '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0),
(2012000000000000007, 'PO202607007', 2010000000000000002, 'S002', '晨岛咖啡贸易', 1930000000000000001, '华东中心仓', 'CANCELLED', 324.00, '2026-07-26', 1900000000000000001, '系统管理员', '2026-07-12 10:30:00', NULL, '', NULL, '2026-07-12 10:30:00', '2026-07-12 10:30:00', 0, '', 0)
ON DUPLICATE KEY UPDATE purchase_no = VALUES(purchase_no), supplier_id = VALUES(supplier_id), supplier_code = VALUES(supplier_code), supplier_name = VALUES(supplier_name),
warehouse_id = VALUES(warehouse_id), warehouse_name = VALUES(warehouse_name), status = VALUES(status), total_amount = VALUES(total_amount), expected_arrival_date = VALUES(expected_arrival_date),
created_by_id = VALUES(created_by_id), created_by_name = VALUES(created_by_name), submitted_at = VALUES(submitted_at), approved_by_id = VALUES(approved_by_id), approved_by_name = VALUES(approved_by_name), approved_at = VALUES(approved_at),
create_time = VALUES(create_time), update_time = VALUES(update_time), deleted = VALUES(deleted), remark = VALUES(remark), version = VALUES(version);

INSERT INTO purchase_order_item (
    id, purchase_order_id, purchase_no, supplier_product_id, product_id, product_code, product_name, unit_name,
    quantity, inbound_qty, unit_price, total_amount, selected_supplier_score, create_time, update_time, remark
) VALUES
(2012100000000000101, 2012000000000000101, 'PO202606001', 2011000000000000001, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 48, 48, 35.20, 1689.60, 9230, '2026-06-13 09:10:00', '2026-06-14 09:12:00', '对应 IB202606140001'),
(2012100000000000102, 2012000000000000101, 'PO202606001', 2011000000000000008, 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 20, 20, 40.50, 810.00, 9140, '2026-06-13 09:10:00', '2026-06-14 09:12:00', '对应 IB202606140001'),
(2012100000000000103, 2012000000000000102, 'PO202606002', 2011000000000000003, 1920000000000000007, 'P000007', '每日坚果混合装', '盒', 60, 0, 68.00, 4080.00, 9180, '2026-06-12 14:20:00', '2026-06-13 15:28:00', '对应 IB202606130006，待确认'),
(2012100000000000104, 2012000000000000102, 'PO202606002', 2011000000000000009, 1920000000000000033, 'P000033', '浓缩洗衣液', '瓶', 27, 0, 28.00, 756.00, 9010, '2026-06-12 14:20:00', '2026-06-13 15:28:00', '对应 IB202606130006，待确认'),
(2012100000000000105, 2012000000000000103, 'PO202606003', 2011000000000000010, 1920000000000000038, 'P000038', '无痕粘钩', '卡', 40, 0, 7.80, 312.00, 8680, '2026-06-11 16:10:00', '2026-06-12 17:36:00', '对应 IB202606120009，待确认'),
(2012100000000000106, 2012000000000000104, 'PO202606004', 2011000000000000005, 1920000000000000021, 'P000021', '中性签字笔', '盒', 20, 0, 12.50, 250.00, 8720, '2026-06-10 11:00:00', '2026-06-11 09:00:00', '历史取消采购单'),
(2012100000000000001, 2012000000000000001, 'PO202607001', 2011000000000000001, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 24, 0, 35.20, 844.80, 9230, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000002, 2012000000000000002, 'PO202607002', 2011000000000000006, 1920000000000000026, 'P000026', 'A4复印纸', '箱', 18, 9, 92.00, 1656.00, 9480, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000003, 2012000000000000003, 'PO202607003', 2011000000000000005, 1920000000000000021, 'P000021', '中性签字笔', '盒', 30, 0, 12.50, 375.00, 8720, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000004, 2012000000000000004, 'PO202607004', 2011000000000000003, 1920000000000000007, 'P000007', '每日坚果混合装', '盒', 16, 0, 68.00, 1088.00, 9180, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000005, 2012000000000000005, 'PO202607005', 2011000000000000001, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 12, 0, 35.20, 422.40, 9230, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000006, 2012000000000000005, 'PO202607005', 2011000000000000002, 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 10, 0, 40.50, 405.00, 8890, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000007, 2012000000000000006, 'PO202607006', 2011000000000000005, 1920000000000000021, 'P000021', '中性签字笔', '盒', 20, 20, 12.50, 250.00, 8720, '2026-07-12 10:30:00', '2026-07-12 10:30:00', ''),
(2012100000000000008, 2012000000000000007, 'PO202607007', 2011000000000000002, 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 8, 0, 40.50, 324.00, 8890, '2026-07-12 10:30:00', '2026-07-12 10:30:00', '')
ON DUPLICATE KEY UPDATE purchase_order_id = VALUES(purchase_order_id), purchase_no = VALUES(purchase_no), supplier_product_id = VALUES(supplier_product_id),
product_id = VALUES(product_id), product_code = VALUES(product_code), product_name = VALUES(product_name), unit_name = VALUES(unit_name), quantity = VALUES(quantity),
inbound_qty = VALUES(inbound_qty), unit_price = VALUES(unit_price), total_amount = VALUES(total_amount), selected_supplier_score = VALUES(selected_supplier_score),
create_time = VALUES(create_time), update_time = VALUES(update_time), remark = VALUES(remark);
