-- MVP 客户销售库表设计：极简版
-- 数据库：MySQL 8
-- 说明：主键由 MyBatis-Plus ASSIGN_ID 生成，因此不使用 AUTO_INCREMENT。

USE erp;

CREATE TABLE IF NOT EXISTS customer (
    id BIGINT NOT NULL COMMENT '客户ID',
    customer_code VARCHAR(64) NOT NULL COMMENT '客户编码',
    customer_name VARCHAR(200) NOT NULL COMMENT '客户名称',
    contact_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '联系人',
    contact_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '联系电话',
    address VARCHAR(255) NOT NULL DEFAULT '' COMMENT '地址',
    credit_limit INT NOT NULL DEFAULT 0 COMMENT '信用额度，放大100倍保存，18000000表示180000.00',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    updated_by_id BIGINT NOT NULL COMMENT '最后维护人ID，新建/编辑/启停时由后端写入',
    updated_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '最后维护人姓名',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_customer_code (customer_code),
    KEY idx_customer_name (customer_name),
    KEY idx_customer_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='客户表';

CREATE TABLE IF NOT EXISTS sales_order (
    id BIGINT NOT NULL COMMENT '销售订单ID',
    sales_no VARCHAR(64) NOT NULL COMMENT '销售单号',
    customer_id BIGINT NOT NULL COMMENT '客户ID',
    customer_code VARCHAR(64) NOT NULL COMMENT '客户编码冗余',
    customer_name VARCHAR(200) NOT NULL COMMENT '客户名称冗余',
    warehouse_id BIGINT NOT NULL COMMENT '出库仓库ID',
    warehouse_name VARCHAR(100) NOT NULL COMMENT '出库仓库名称冗余',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT、SUBMITTED、APPROVED、PARTIAL_OUTBOUND、OUTBOUND_DONE、CANCELLED',
    total_amount INT NOT NULL DEFAULT 0 COMMENT '订单总金额，放大100倍保存，0表示0.00',
    expected_delivery_date DATE DEFAULT NULL COMMENT '预计发货日期',
    locked_at DATETIME DEFAULT NULL COMMENT '库存锁定时间',
    created_by_id BIGINT DEFAULT NULL COMMENT '创建人ID',
    created_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '创建人姓名',
    submitted_at DATETIME DEFAULT NULL COMMENT '提交时间',
    submitted_by_id BIGINT DEFAULT NULL COMMENT '提交人ID',
    submitted_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '提交人姓名',
    approved_by_id BIGINT DEFAULT NULL COMMENT '审核人ID',
    approved_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '审核人姓名',
    approved_at DATETIME DEFAULT NULL COMMENT '审核时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sales_order_no (sales_no),
    KEY idx_sales_order_customer (customer_id),
    KEY idx_sales_order_warehouse (warehouse_id),
    KEY idx_sales_order_status (status),
    KEY idx_sales_order_create_time (create_time),
    KEY idx_sales_order_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售订单主表';

CREATE TABLE IF NOT EXISTS sales_order_item (
    id BIGINT NOT NULL COMMENT '明细ID',
    sales_order_id BIGINT NOT NULL COMMENT '销售订单ID',
    sales_no VARCHAR(64) NOT NULL COMMENT '销售单号冗余',
    product_id BIGINT NOT NULL COMMENT '产品ID',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码冗余',
    product_name VARCHAR(200) NOT NULL COMMENT '产品名称冗余',
    unit_name VARCHAR(32) NOT NULL DEFAULT '件' COMMENT '单位名称冗余',
    quantity_precision TINYINT NOT NULL DEFAULT 0 COMMENT '数量小数位快照：0-2，下单时从 product.quantity_precision 固化',
    quantity BIGINT NOT NULL DEFAULT 0 COMMENT '销售数量，放大100倍保存，N 表示 N/100.00',
    locked_qty BIGINT NOT NULL DEFAULT 0 COMMENT '已锁定库存数量，放大100倍保存',
    outbound_qty BIGINT NOT NULL DEFAULT 0 COMMENT '已出库数量，放大100倍保存',
    unit_price INT NOT NULL DEFAULT 0 COMMENT '销售单价，放大100倍保存，0表示0.00',
    total_amount INT NOT NULL DEFAULT 0 COMMENT '明细金额，放大100倍保存，0表示0.00',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    KEY idx_sales_order_item_order (sales_order_id),
    KEY idx_sales_order_item_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售订单明细表';

-- ============================================================
-- 销售模块种子数据：产品、仓库和用户均引用 001/002/003 的既有种子。
-- 202 号段由本文件专用；历史销售单与仓库工作单、库存流水按来源单号关联。
-- ============================================================

INSERT INTO customer (
    id, customer_code, customer_name, contact_name, contact_phone, address, credit_limit,
    status, create_time, update_time, updated_by_id, updated_by_name, deleted, remark, version
) VALUES
(2020000000000000001, 'C001', '上海林间便利连锁', '秦夏', '021-7728-2001', '上海市浦东新区张江镇', 18000000, 1, '2026-06-04 09:20:00', '2026-06-14 15:20:00',  1900000000000000001, '系统管理员', 0, '重点销售客户，订单创建时需关注信用额度', 0),
(2020000000000000002, 'C002', '杭州蓝湖办公采购', '许然', '0571-7728-2002', '杭州市西湖区文三路', 9000000, 1, '2026-06-05 09:20:00', '2026-06-15 15:20:00',  1900000000000000001, '系统管理员', 0, '重点销售客户，订单创建时需关注信用额度', 0),
(2020000000000000003, 'C003', '南京星火校园超市', '陈可', '025-7728-2003', '南京市栖霞区仙林大道', 12000000, 1, '2026-06-06 09:20:00', '2026-06-16 15:20:00',  1900000000000000001, '系统管理员', 0, '重点销售客户，订单创建时需关注信用额度', 0),
(2020000000000000004, 'C004', '广州云帆商贸', '林沐', '020-7728-2004', '广州市天河区体育西路', 6000000, 1, '2026-06-07 09:20:00', '2026-06-17 15:20:00',  1900000000000000001, '系统管理员', 0, '', 0),
(2020000000000000005, 'C005', '苏州森活社区团购', '周晨', '0512-7728-2005', '苏州市工业园区星湖街', 7500000, 0, '2026-06-08 09:20:00', '2026-06-14 15:20:00',  1900000000000000001, '系统管理员', 0, '', 0),
(2020000000000000101, 'C006', '上海星河便利店', '陈宁', '021-7728-2101', '上海市浦东新区张江镇', 6000000, 1, '2026-06-01 09:20:00', '2026-06-14 10:05:00',  1900000000000000001, '系统管理员', 0, '历史销售来源客户', 0),
(2020000000000000102, 'C007', '成都青柠商贸', '李青', '028-7728-2102', '成都市武侯区天府大道', 8000000, 1, '2026-06-01 09:20:00', '2026-06-13 14:50:00',  1900000000000000001, '系统管理员', 0, '历史销售来源客户', 0),
(2020000000000000103, 'C008', '杭州电商客户', '周帆', '0571-7728-2103', '杭州市余杭区电商园', 10000000, 1, '2026-06-01 09:20:00', '2026-06-11 18:05:00',  1900000000000000001, '系统管理员', 0, '历史销售来源客户', 0),
(2020000000000000104, 'C009', '广州天河门店', '何俊', '020-7728-2104', '广州市天河区体育西路', 7000000, 1, '2026-06-01 09:20:00', '2026-06-10 09:40:00',  1900000000000000001, '系统管理员', 0, '历史销售来源客户', 0),
(2020000000000000105, 'C010', '武汉江岸客户', '王江', '027-7728-2105', '武汉市江岸区解放大道', 6000000, 1, '2026-06-01 09:20:00', '2026-06-12 16:18:00',  1900000000000000001, '系统管理员', 0, '历史销售退货来源客户', 0)
ON DUPLICATE KEY UPDATE customer_code = VALUES(customer_code), customer_name = VALUES(customer_name), contact_name = VALUES(contact_name),
contact_phone = VALUES(contact_phone), address = VALUES(address), credit_limit = VALUES(credit_limit), status = VALUES(status),
create_time = VALUES(create_time), update_time = VALUES(update_time), updated_by_id = VALUES(updated_by_id), updated_by_name = VALUES(updated_by_name),
deleted = VALUES(deleted), remark = VALUES(remark), version = VALUES(version);

INSERT INTO sales_order (
    id, sales_no, customer_id, customer_code, customer_name, warehouse_id, warehouse_name,
    status, total_amount, expected_delivery_date, locked_at, created_by_id, created_by_name,
    submitted_at, approved_by_id, approved_by_name, approved_at, create_time, update_time, deleted, remark, version
) VALUES
(2022000000000000101, 'SO202606001', 2020000000000000101, 'C006', '上海星河便利店', 1930000000000000001, '华东中心仓', 'OUTBOUND_DONE', 39920, '2026-06-14', NULL, 1900000000000000003, '销售主管', '2026-06-13 09:20:00', 1900000000000000003, '销售主管', '2026-06-13 10:00:00', '2026-06-13 09:20:00', '2026-06-14 10:05:00',  1900000000000000001, '系统管理员', 0, '对应 OB202606140002，已确认出库', 0),
(2022000000000000102, 'SO202606002', 2020000000000000102, 'C007', '成都青柠商贸', 1930000000000000004, '西南中心仓', 'OUTBOUND_DONE', 20280, '2026-06-13', NULL, 1900000000000000003, '销售主管', '2026-06-12 13:50:00', 1900000000000000003, '销售主管', '2026-06-12 14:20:00', '2026-06-12 13:50:00', '2026-06-13 14:50:00',  1900000000000000001, '系统管理员', 0, '对应 OB202606130007，已确认出库', 0),
(2022000000000000103, 'SO202606003', 2020000000000000103, 'C008', '杭州电商客户', 1930000000000000007, '杭州电商仓', 'OUTBOUND_DONE', 41400, '2026-06-11', NULL, 1900000000000000003, '销售主管', '2026-06-10 17:10:00', 1900000000000000003, '销售主管', '2026-06-10 17:40:00', '2026-06-10 17:10:00', '2026-06-11 18:05:00',  1900000000000000001, '系统管理员', 0, '对应 OB202606110012，停用产品仅保留历史追溯', 0),
(2022000000000000104, 'SO202606004', 2020000000000000104, 'C009', '广州天河门店', 1930000000000000002, '华南中心仓', 'APPROVED', 119000, '2026-06-10', '2026-06-10 09:40:00', 1900000000000000003, '销售主管', '2026-06-09 16:00:00', 1900000000000000003, '销售主管', '2026-06-09 16:30:00', '2026-06-09 16:00:00', '2026-06-10 09:40:00',  1900000000000000001, '系统管理员', 0, '对应 OB202606100015，待确认出库', 0),
(2022000000000000105, 'SO202606005', 2020000000000000104, 'C009', '广州天河门店', 1930000000000000002, '华南中心仓', 'OUTBOUND_DONE', 15000, '2026-06-14', NULL, 1900000000000000003, '销售主管', '2026-06-13 10:30:00', 1900000000000000003, '销售主管', '2026-06-13 10:50:00', '2026-06-13 10:30:00', '2026-06-14 11:15:00',  1900000000000000001, '系统管理员', 0, '对应 SRO202606001，已完成出库后全量销售退货', 0),
(2022000000000000106, 'SO202606006', 2020000000000000105, 'C010', '武汉江岸客户', 1930000000000000005, '武汉中转仓', 'OUTBOUND_DONE', 31200, '2026-06-12', NULL, 1900000000000000003, '销售主管', '2026-06-11 15:30:00', 1900000000000000003, '销售主管', '2026-06-11 15:50:00', '2026-06-11 15:30:00', '2026-06-12 16:18:00',  1900000000000000001, '系统管理员', 0, '对应 SRO202606002，已完成出库后全量销售退货', 0),
(2022000000000000001, 'SO202607001', 2020000000000000001, 'C001', '上海林间便利连锁', 1930000000000000001, '华东中心仓', 'APPROVED', 139080, '2026-07-27', '2026-07-13 09:15:00', 1900000000000000003, '销售主管', '2026-07-12 10:40:00', 1900000000000000003, '销售主管', '2026-07-13 09:30:00', '2026-07-12 10:40:00', '2026-07-12 10:40:00',  1900000000000000001, '系统管理员', 0, '', 0),
(2022000000000000002, 'SO202607002', 2020000000000000003, 'C003', '南京星火校园超市', 1930000000000000002, '华南中心仓', 'PARTIAL_OUTBOUND', 39800, '2026-07-24', '2026-07-13 09:15:00', 1900000000000000003, '销售主管', '2026-07-12 10:40:00', 1900000000000000003, '销售主管', '2026-07-13 09:30:00', '2026-07-12 10:40:00', '2026-07-12 10:40:00',  1900000000000000001, '系统管理员', 0, '', 0),
(2022000000000000003, 'SO202607003', 2020000000000000004, 'C004', '广州云帆商贸', 1930000000000000008, '南京备货仓', 'DRAFT', 71400, '2026-07-30', NULL, 1900000000000000001, '系统管理员', NULL, NULL, '', NULL, '2026-07-12 10:40:00', '2026-07-12 10:40:00',  1900000000000000001, '系统管理员', 0, '', 0),
(2022000000000000004, 'SO202607004', 2020000000000000002, 'C002', '杭州蓝湖办公采购', 1930000000000000001, '华东中心仓', 'SUBMITTED', 69000, '2026-07-30', '2026-07-13 09:15:00', 1900000000000000003, '销售主管', '2026-07-12 10:40:00', NULL, '', NULL, '2026-07-12 10:40:00', '2026-07-12 10:40:00',  1900000000000000001, '系统管理员', 0, '', 0),
(2022000000000000005, 'SO202607005', 2020000000000000003, 'C003', '南京星火校园超市', 1930000000000000002, '华南中心仓', 'SUBMITTED', 95200, '2026-07-30', '2026-07-13 09:15:00', 1900000000000000003, '销售主管', '2026-07-12 10:40:00', NULL, '', NULL, '2026-07-12 10:40:00', '2026-07-12 10:40:00',  1900000000000000001, '系统管理员', 0, '', 0),
(2022000000000000006, 'SO202607006', 2020000000000000001, 'C001', '上海林间便利连锁', 1930000000000000001, '华东中心仓', 'OUTBOUND_DONE', 29940, '2026-07-20', NULL, 1900000000000000003, '销售主管', '2026-07-12 10:40:00', 1900000000000000003, '销售主管', '2026-07-13 09:30:00', '2026-07-12 10:40:00', '2026-07-12 10:40:00',  1900000000000000001, '系统管理员', 0, '', 0),
(2022000000000000007, 'SO202607007', 2020000000000000004, 'C004', '广州云帆商贸', 1930000000000000008, '南京备货仓', 'CANCELLED', 39600, '2026-07-26', NULL, 1900000000000000001, '系统管理员', '2026-07-12 10:40:00', NULL, '', NULL, '2026-07-12 10:40:00', '2026-07-12 10:40:00',  1900000000000000001, '系统管理员', 0, '', 0)
ON DUPLICATE KEY UPDATE sales_no = VALUES(sales_no), customer_id = VALUES(customer_id), customer_code = VALUES(customer_code), customer_name = VALUES(customer_name),
warehouse_id = VALUES(warehouse_id), warehouse_name = VALUES(warehouse_name), status = VALUES(status), total_amount = VALUES(total_amount), expected_delivery_date = VALUES(expected_delivery_date),
locked_at = VALUES(locked_at), created_by_id = VALUES(created_by_id), created_by_name = VALUES(created_by_name), submitted_at = VALUES(submitted_at), approved_by_id = VALUES(approved_by_id),
approved_by_name = VALUES(approved_by_name), approved_at = VALUES(approved_at), create_time = VALUES(create_time), update_time = VALUES(update_time), deleted = VALUES(deleted), remark = VALUES(remark), version = VALUES(version);

INSERT INTO sales_order_item (
    id, sales_order_id, sales_no, product_id, product_code, product_name, unit_name,
    quantity_precision, quantity, locked_qty, outbound_qty, unit_price, total_amount, create_time, update_time, remark
) VALUES
(2022100000000000101, 2022000000000000101, 'SO202606001', 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 800, 0, 800, 4990, 39920, '2026-06-13 09:20:00', '2026-06-14 10:05:00', '对应 OB202606140002'),
(2022100000000000102, 2022000000000000102, 'SO202606002', 1920000000000000034, 'P000034', '厨房清洁湿巾', '包', 0, 1200, 0, 1200, 1690, 20280, '2026-06-12 13:50:00', '2026-06-13 14:50:00', '对应 OB202606130007'),
(2022100000000000103, 2022000000000000103, 'SO202606003', 1920000000000000044, 'P000044', '无线办公鼠标', '个', 0, 600, 0, 600, 6900, 41400, '2026-06-10 17:10:00', '2026-06-11 18:05:00', '停用产品仅保留历史追溯'),
(2022100000000000104, 2022000000000000104, 'SO202606004', 1920000000000000026, 'P000026', 'A4复印纸', '箱', 0, 1000, 1000, 0, 11900, 119000, '2026-06-09 16:00:00', '2026-06-10 09:40:00', '对应 OB202606100015，待确认'),
(2022100000000000105, 2022000000000000105, 'SO202606005', 1920000000000000027, 'P000027', '热敏标签纸', '卷', 0, 1000, 0, 1000, 1500, 15000, '2026-06-13 10:30:00', '2026-06-14 11:15:00', '对应 SRO202606001，已完成出库后退货 10 卷'),
(2022100000000000106, 2022000000000000106, 'SO202606006', 1920000000000000008, 'P000008', '海盐苏打饼干', '箱', 0, 400, 0, 400, 7800, 31200, '2026-06-11 15:30:00', '2026-06-12 16:18:00', '对应 SRO202606002，已完成出库后退货 4 箱'),
(2022100000000000001, 2022000000000000001, 'SO202607001', 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 1200, 1200, 0, 4990, 59880, '2026-07-12 10:40:00', '2026-07-12 10:40:00', ''),
(2022100000000000002, 2022000000000000001, 'SO202607001', 1920000000000000007, 'P000007', '每日坚果混合装', '盒', 0, 800, 800, 0, 9900, 79200, '2026-07-12 10:40:00', '2026-07-12 10:40:00', ''),
(2022100000000000003, 2022000000000000002, 'SO202607002', 1920000000000000021, 'P000021', '中性签字笔', '盒', 0, 2000, 1000, 1000, 1990, 39800, '2026-07-12 10:40:00', '2026-07-12 10:40:00', ''),
(2022100000000000004, 2022000000000000003, 'SO202607003', 1920000000000000026, 'P000026', 'A4复印纸', '箱', 0, 600, 0, 0, 11900, 71400, '2026-07-12 10:40:00', '2026-07-12 10:40:00', ''),
(2022100000000000005, 2022000000000000004, 'SO202607004', 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 0, 1000, 1000, 0, 6900, 69000, '2026-07-12 10:40:00', '2026-07-12 10:40:00', ''),
(2022100000000000006, 2022000000000000005, 'SO202607005', 1920000000000000026, 'P000026', 'A4复印纸', '箱', 0, 800, 800, 0, 11900, 95200, '2026-07-12 10:40:00', '2026-07-12 10:40:00', ''),
(2022100000000000007, 2022000000000000006, 'SO202607006', 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 600, 0, 600, 4990, 29940, '2026-07-12 10:40:00', '2026-07-12 10:40:00', ''),
(2022100000000000008, 2022000000000000007, 'SO202607007', 1920000000000000007, 'P000007', '每日坚果混合装', '盒', 0, 400, 0, 0, 9900, 39600, '2026-07-12 10:40:00', '2026-07-12 10:40:00', '')
ON DUPLICATE KEY UPDATE sales_order_id = VALUES(sales_order_id), sales_no = VALUES(sales_no), product_id = VALUES(product_id),
product_code = VALUES(product_code), product_name = VALUES(product_name), unit_name = VALUES(unit_name), quantity_precision = VALUES(quantity_precision),
quantity = VALUES(quantity), locked_qty = VALUES(locked_qty), outbound_qty = VALUES(outbound_qty), unit_price = VALUES(unit_price), total_amount = VALUES(total_amount),
create_time = VALUES(create_time), update_time = VALUES(update_time), remark = VALUES(remark);
