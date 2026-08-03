-- MVP 仓库库存库表设计：入库单/出库单/库存流水分层版
-- 数据库：MySQL 8
-- 说明：主键由 MyBatis-Plus ASSIGN_ID 生成，因此不使用 AUTO_INCREMENT。
-- 业务规则：DRAFT 可修改仓库、手工来源信息和产品明细；PENDING_CONFIRM 锁定仓库、来源和产品结构，仅允许有权限人员调整本次数量、合格数量、不合格数量和备注；CONFIRMED 后禁止修改。
-- 业务规则：确认入库单/出库单前，前端必须展示完整详情和全部产品明细，并从详情页发起二次确认，确认后才生成 stock_bill 库存流水；待确认出库单的本次数量必须已反映在 locked_qty 中。

USE erp;

CREATE TABLE IF NOT EXISTS warehouse (
    id BIGINT NOT NULL COMMENT '仓库ID',
    warehouse_code VARCHAR(64) NOT NULL COMMENT '仓库编码',
    warehouse_name VARCHAR(100) NOT NULL COMMENT '仓库名称',
    contact_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '联系人',
    contact_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '联系电话',
    address VARCHAR(255) NOT NULL DEFAULT '' COMMENT '仓库地址',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_warehouse_code (warehouse_code),
    KEY idx_warehouse_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='仓库表';

CREATE TABLE IF NOT EXISTS warehouse_stock (
    id BIGINT NOT NULL COMMENT '库存ID',
    warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
    warehouse_code VARCHAR(64) NOT NULL COMMENT '仓库编码冗余',
    warehouse_name VARCHAR(100) NOT NULL COMMENT '仓库名称冗余',
    product_id BIGINT NOT NULL COMMENT '产品ID',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码冗余',
    product_name VARCHAR(200) NOT NULL COMMENT '产品名称冗余',
    unit_name VARCHAR(32) NOT NULL DEFAULT '件' COMMENT '单位名称冗余',
    stock_qty BIGINT NOT NULL DEFAULT 0 COMMENT '当前库存数量，按100倍整数存储',
    locked_qty BIGINT NOT NULL DEFAULT 0 COMMENT '锁定库存数量，按100倍整数存储',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_warehouse_stock_product (warehouse_id, product_id),
    KEY idx_warehouse_stock_product (product_id),
    KEY idx_warehouse_stock_product_code (product_code),
    KEY idx_warehouse_stock_product_name (product_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存余额表';

CREATE TABLE IF NOT EXISTS inbound_bill (
    id BIGINT NOT NULL COMMENT '入库单ID',
    inbound_no VARCHAR(64) NOT NULL COMMENT '入库单号',
    inbound_type VARCHAR(32) NOT NULL COMMENT '类型：PURCHASE_IN、SALES_RETURN、ADJUST_IN',
    source_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '来源类型：PURCHASE_ORDER、SALES_RETURN_ORDER、STOCK_ADJUST',
    source_id BIGINT DEFAULT NULL COMMENT '来源单据ID',
    source_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '来源单据号',
    source_party_id BIGINT DEFAULT NULL COMMENT '来源对象ID，采购入库为供应商、销售退货为客户、调整入库为受影响仓库',
    source_party_name VARCHAR(200) NOT NULL DEFAULT '' COMMENT '来源供应商、客户或调整仓库名称快照',
    entry_mode VARCHAR(32) NOT NULL DEFAULT 'SOURCE_GENERATED' COMMENT '录入方式：SOURCE_GENERATED、MANUAL_SUPPLEMENT、MANUAL_ADJUSTMENT',
    warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
    warehouse_name VARCHAR(100) NOT NULL COMMENT '仓库名称快照',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRM' COMMENT '状态：DRAFT、PENDING_CONFIRM、CONFIRMED、CANCELLED',
    confirmed_by_id BIGINT DEFAULT NULL COMMENT '确认人ID',
    confirmed_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '确认人姓名',
    confirmed_at DATETIME DEFAULT NULL COMMENT '确认时间',
    created_by_id BIGINT DEFAULT NULL COMMENT '创建人ID',
    created_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '创建人姓名',
    responsible_by_id BIGINT DEFAULT NULL COMMENT '业务负责人ID，确认时填入审核人',
    responsible_by_name VARCHAR(100) DEFAULT NULL COMMENT '业务负责人姓名快照，确认时填入审核人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    manual_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '手工补录或库存调整原因',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_inbound_bill_no (inbound_no),
    KEY idx_inbound_bill_source (source_type, source_id),
    KEY idx_inbound_bill_source_no (source_no),
    KEY idx_inbound_bill_party (source_party_id),
    KEY idx_inbound_bill_warehouse (warehouse_id),
    KEY idx_inbound_bill_type_status (inbound_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库单主表';

CREATE TABLE IF NOT EXISTS inbound_bill_item (
    id BIGINT NOT NULL COMMENT '入库单明细ID',
    inbound_bill_id BIGINT NOT NULL COMMENT '入库单ID',
    inbound_no VARCHAR(64) NOT NULL COMMENT '入库单号冗余',
    source_item_id BIGINT DEFAULT NULL COMMENT '来源单据明细ID',
    product_id BIGINT NOT NULL COMMENT '产品ID',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码快照',
    product_name VARCHAR(200) NOT NULL COMMENT '产品名称快照',
    unit_name VARCHAR(32) NOT NULL DEFAULT '件' COMMENT '单位名称快照',
    quantity_precision TINYINT NOT NULL DEFAULT 0 COMMENT '数量小数位快照：0-2',
    plan_qty BIGINT DEFAULT NULL COMMENT '来源计划数量，按100倍整数存储；线下补录或调整无来源时为空',
    processed_qty BIGINT DEFAULT NULL COMMENT '生成本单前累计已入库数量，按100倍整数存储；线下补录或调整无来源时为空',
    current_qty BIGINT NOT NULL DEFAULT 0 COMMENT '本次入库数量，按100倍整数存储',
    pending_qty BIGINT DEFAULT NULL COMMENT '确认本单后剩余未入库数量，按100倍整数存储；线下补录或调整无来源时为空',
    qualified_qty BIGINT NOT NULL DEFAULT 0 COMMENT '合格数量，按100倍整数存储；采购入库和销售退货入库使用',
    defective_qty BIGINT NOT NULL DEFAULT 0 COMMENT '不合格数量，按100倍整数存储；采购入库和销售退货入库使用',
    stock_bill_item_id BIGINT DEFAULT NULL COMMENT '确认后生成的库存流水明细ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_inbound_bill_item_bill (inbound_bill_id),
    KEY idx_inbound_bill_item_product (product_id),
    KEY idx_inbound_bill_item_source_item (source_item_id),
    KEY idx_inbound_bill_item_stock_item (stock_bill_item_id),
    CONSTRAINT chk_inbound_bill_item_quantity_precision CHECK (quantity_precision BETWEEN 0 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='入库单明细表';

CREATE TABLE IF NOT EXISTS outbound_bill (
    id BIGINT NOT NULL COMMENT '出库单ID',
    outbound_no VARCHAR(64) NOT NULL COMMENT '出库单号',
    outbound_type VARCHAR(32) NOT NULL COMMENT '类型：SALES_OUT、PURCHASE_RETURN、ADJUST_OUT',
    source_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '来源类型：SALES_ORDER、PURCHASE_RETURN_ORDER、STOCK_ADJUST',
    source_id BIGINT DEFAULT NULL COMMENT '来源单据ID',
    source_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '来源单据号',
    source_party_id BIGINT DEFAULT NULL COMMENT '来源对象ID，销售出库为客户、采购退货为供应商、调整出库为受影响仓库',
    source_party_name VARCHAR(200) NOT NULL DEFAULT '' COMMENT '来源客户、供应商或调整仓库名称快照',
    entry_mode VARCHAR(32) NOT NULL DEFAULT 'SOURCE_GENERATED' COMMENT '录入方式：SOURCE_GENERATED、MANUAL_SUPPLEMENT、MANUAL_ADJUSTMENT',
    warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
    warehouse_name VARCHAR(100) NOT NULL COMMENT '仓库名称快照',
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING_CONFIRM' COMMENT '状态：DRAFT、PENDING_CONFIRM、CONFIRMED、CANCELLED',
    confirmed_by_id BIGINT DEFAULT NULL COMMENT '确认人ID',
    confirmed_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '确认人姓名',
    confirmed_at DATETIME DEFAULT NULL COMMENT '确认时间',
    created_by_id BIGINT DEFAULT NULL COMMENT '创建人ID',
    created_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '创建人姓名',
    responsible_by_id BIGINT DEFAULT NULL COMMENT '业务负责人ID，确认时填入审核人',
    responsible_by_name VARCHAR(100) DEFAULT NULL COMMENT '业务负责人姓名快照，确认时填入审核人',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    manual_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '手工补录或库存调整原因',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbound_bill_no (outbound_no),
    KEY idx_outbound_bill_source (source_type, source_id),
    KEY idx_outbound_bill_source_no (source_no),
    KEY idx_outbound_bill_party (source_party_id),
    KEY idx_outbound_bill_warehouse (warehouse_id),
    KEY idx_outbound_bill_type_status (outbound_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出库单主表';

CREATE TABLE IF NOT EXISTS outbound_bill_item (
    id BIGINT NOT NULL COMMENT '出库单明细ID',
    outbound_bill_id BIGINT NOT NULL COMMENT '出库单ID',
    outbound_no VARCHAR(64) NOT NULL COMMENT '出库单号冗余',
    source_item_id BIGINT DEFAULT NULL COMMENT '来源单据明细ID',
    product_id BIGINT NOT NULL COMMENT '产品ID',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码快照',
    product_name VARCHAR(200) NOT NULL COMMENT '产品名称快照',
    unit_name VARCHAR(32) NOT NULL DEFAULT '件' COMMENT '单位名称快照',
    quantity_precision TINYINT NOT NULL DEFAULT 0 COMMENT '数量小数位快照：0-2',
    plan_qty BIGINT DEFAULT NULL COMMENT '来源计划数量，按100倍整数存储；线下补录或调整无来源时为空',
    processed_qty BIGINT DEFAULT NULL COMMENT '生成本单前累计已出库数量，按100倍整数存储；线下补录或调整无来源时为空',
    current_qty BIGINT NOT NULL DEFAULT 0 COMMENT '本次出库数量，按100倍整数存储',
    pending_qty BIGINT DEFAULT NULL COMMENT '确认本单后剩余未出库数量，按100倍整数存储；线下补录或调整无来源时为空',
    qualified_qty BIGINT NOT NULL DEFAULT 0 COMMENT '合格数量，按100倍整数存储；采购退货出库使用',
    defective_qty BIGINT NOT NULL DEFAULT 0 COMMENT '不合格数量，按100倍整数存储；采购退货出库使用',
    stock_bill_item_id BIGINT DEFAULT NULL COMMENT '确认后生成的库存流水明细ID',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_outbound_bill_item_bill (outbound_bill_id),
    KEY idx_outbound_bill_item_product (product_id),
    KEY idx_outbound_bill_item_source_item (source_item_id),
    KEY idx_outbound_bill_item_stock_item (stock_bill_item_id),
    CONSTRAINT chk_outbound_bill_item_quantity_precision CHECK (quantity_precision BETWEEN 0 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出库单明细表';

CREATE TABLE IF NOT EXISTS stock_bill (
    id BIGINT NOT NULL COMMENT '库存流水凭证ID',
    bill_no VARCHAR(64) NOT NULL COMMENT '库存流水号',
    bill_type VARCHAR(32) NOT NULL COMMENT '类型：PURCHASE_IN、SALES_OUT、PURCHASE_RETURN、SALES_RETURN、ADJUST_IN、ADJUST_OUT',
    work_bill_id BIGINT NOT NULL COMMENT '已确认入库单或出库单ID；单据表由bill_type推导',
    business_source_id BIGINT DEFAULT NULL COMMENT '原业务单据ID',
    business_source_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '原业务单据号',
    entry_mode VARCHAR(32) NOT NULL DEFAULT 'SOURCE_GENERATED' COMMENT '录入方式：SOURCE_GENERATED、MANUAL_SUPPLEMENT、MANUAL_ADJUSTMENT',
    warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
    warehouse_name VARCHAR(100) NOT NULL COMMENT '仓库名称快照',
    confirmed_by_id BIGINT NOT NULL COMMENT '确认人ID',
    confirmed_by_name VARCHAR(100) NOT NULL COMMENT '确认人姓名',
    confirmed_at DATETIME NOT NULL COMMENT '确认时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_bill_no (bill_no),
    KEY idx_stock_bill_work_bill (work_bill_id),
    KEY idx_stock_bill_business_source (bill_type, business_source_id),
    KEY idx_stock_bill_business_source_no (business_source_no),
    KEY idx_stock_bill_entry_mode_confirmed_at (entry_mode, confirmed_at),
    KEY idx_stock_bill_warehouse (warehouse_id),
    KEY idx_stock_bill_type_confirmed_at (bill_type, confirmed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存流水凭证主表';

CREATE TABLE IF NOT EXISTS stock_bill_item (
    id BIGINT NOT NULL COMMENT '库存流水凭证明细ID',
    bill_id BIGINT NOT NULL COMMENT '库存流水凭证ID',
    work_bill_item_id BIGINT NOT NULL COMMENT '已确认入库单明细或出库单明细ID',
    business_source_item_id BIGINT DEFAULT NULL COMMENT '原业务来源明细ID',
    product_id BIGINT NOT NULL COMMENT '产品ID',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码快照',
    product_name VARCHAR(200) NOT NULL COMMENT '产品名称快照',
    unit_name VARCHAR(32) NOT NULL DEFAULT '件' COMMENT '单位名称快照',
    quantity_precision TINYINT NOT NULL DEFAULT 0 COMMENT '数量小数位快照：0-2',
    qualified_qty BIGINT NOT NULL DEFAULT 0 COMMENT '合格数量，按100倍整数存储；仅质检适用类型使用',
    defective_qty BIGINT NOT NULL DEFAULT 0 COMMENT '不合格数量，按100倍整数存储；仅质检适用类型使用',
    before_qty BIGINT NOT NULL DEFAULT 0 COMMENT '变动前库存，按100倍整数存储',
    change_qty BIGINT NOT NULL DEFAULT 0 COMMENT '库存变动数量，入库为正、出库为负，按100倍整数存储',
    after_qty BIGINT NOT NULL DEFAULT 0 COMMENT '变动后库存，按100倍整数存储',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_stock_bill_item_bill (bill_id),
    KEY idx_stock_bill_item_product (product_id),
    KEY idx_stock_bill_item_work_item (work_bill_item_id),
    KEY idx_stock_bill_item_business_source_item (business_source_item_id),
    CONSTRAINT chk_stock_bill_item_quantity_precision CHECK (quantity_precision BETWEEN 0 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存流水凭证明细表';

-- ============================================================
-- 仓库库存模块种子数据
-- 说明：
-- 1. 以 qiheng-2026_07_17_02_57_22-dump.sql 为基线，现有 193 号段数据仅使用 INSERT IGNORE 补空库，绝不覆盖。
-- 2. 新增种子逐项对应前端 warehouse Mock；系统操作人统一使用 warehouse01（1900000000000000004）。
-- 3. 采购、销售和退货来源表尚未初始化，保留来源类型、单号和对象名称快照，来源ID统一为 NULL。
-- 4. BIGINT 数量字段统一按 100 倍整数存储，例如 12 箱保存为 1200。
-- 5. warehouse_stock 表示当前余额，期间存在未展示历史流水，不能仅靠下方工作单反推全部余额。
-- 6. 最终包含 17 张工作单、19 条工作单明细；仅 11 张 CONFIRMED 工作单生成 11 张流水和 12 条流水明细。
-- 7. 新增种子使用全字段 UPSERT，可重复执行并纠正 seed-owned 行；脚本不执行数据清理。
-- ============================================================

START TRANSACTION;

INSERT IGNORE INTO warehouse (
    id, warehouse_code, warehouse_name, contact_name, contact_phone, address,
    status, create_time, update_time, deleted, remark, version
) VALUES
(1930000000000000001, 'WH001', '华东中心仓', '周宁', '021-5558-1001', '上海市嘉定区汇源路88号', 1, '2026-06-01 09:30:00', '2026-07-01 10:05:00', 0, '华东区域日常收发与调拨仓库', 0),
(1930000000000000003, 'WH003', '华北中心仓', '许峰', '010-5558-1003', '北京市顺义区物流园北路16号', 1, '2026-06-03 09:30:00', '2026-07-01 09:12:00', 0, '华北区域日常收发与调拨仓库', 0);

INSERT INTO warehouse (
    id, warehouse_code, warehouse_name, contact_name, contact_phone, address,
    status, create_time, update_time, deleted, remark, version
) VALUES
(1930000000000000002, 'WH002', '华南中心仓', '林敏', '020-5558-1002', '广州市黄埔区开创大道168号', 1, '2026-06-02 09:30:00', '2026-06-11 15:20:00', 0, '华南区域日常收发与调拨仓库', 0),
(1930000000000000004, 'WH004', '西南中心仓', '高洁', '028-5558-1004', '成都市双流区航空港大道52号', 1, '2026-06-04 09:30:00', '2026-06-13 15:20:00', 0, '西南区域日常收发与调拨仓库', 0),
(1930000000000000005, 'WH005', '武汉中转仓', '陈航', '027-5558-1005', '武汉市东西湖区新城十一路30号', 1, '2026-06-05 09:30:00', '2026-06-10 15:20:00', 0, '', 0),
(1930000000000000006, 'WH006', '西安中转仓', '赵然', '029-5558-1006', '西安市灞桥区港务大道109号', 1, '2026-06-06 09:30:00', '2026-06-11 15:20:00', 0, '', 0),
(1930000000000000007, 'WH007', '杭州电商仓', '沈佳', '0571-5558-1007', '杭州市余杭区仁和街道云创路9号', 1, '2026-06-07 09:30:00', '2026-06-12 15:20:00', 0, '', 0),
(1930000000000000008, 'WH008', '南京备货仓', '王澄', '025-5558-1008', '南京市江宁区秣陵工业园21号', 1, '2026-06-08 09:30:00', '2026-06-13 15:20:00', 0, '', 0),
(1930000000000000009, 'WH009', '青岛周转仓', '方圆', '0532-5558-1009', '青岛市城阳区双元路66号', 0, '2026-06-01 09:30:00', '2026-06-10 15:20:00', 0, '', 0),
(1930000000000000010, 'WH010', '长沙临时仓', '唐月', '0731-5558-1010', '长沙市雨花区环保东路18号', 0, '2026-06-02 09:30:00', '2026-06-11 15:20:00', 0, '', 0),
(1930000000000000011, 'WH011', '郑州临时仓', '', '', '郑州市经开区航海东路1268号', 1, '2026-06-03 09:30:00', '2026-06-12 15:20:00', 0, '', 0),
(1930000000000000012, 'WH012', '合肥样品仓', '宋哲', '0551-5558-1012', '合肥市蜀山区创新大道2800号', 1, '2026-06-04 09:30:00', '2026-06-13 15:20:00', 0, '', 0)
ON DUPLICATE KEY UPDATE warehouse_code = VALUES(warehouse_code), warehouse_name = VALUES(warehouse_name),
contact_name = VALUES(contact_name), contact_phone = VALUES(contact_phone), address = VALUES(address),
status = VALUES(status), create_time = VALUES(create_time), update_time = VALUES(update_time),
deleted = VALUES(deleted), remark = VALUES(remark), version = VALUES(version);

INSERT IGNORE INTO warehouse_stock (
    id, warehouse_id, warehouse_code, warehouse_name,
    product_id, product_code, product_name, unit_name,
    stock_qty, locked_qty, create_time, update_time, version
) VALUES
(1931000000000000001, 1930000000000000003, 'WH003', '华北中心仓', 1920000000000000007, 'P000007', '每日坚果混合装', '盒', 3100, 0, '2026-07-01 09:12:00', '2026-07-01 09:12:00', 0),
(1931000000000000002, 1930000000000000001, 'WH001', '华东中心仓', 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 700, 0, '2026-07-01 10:05:00', '2026-07-01 10:05:00', 0);

INSERT INTO warehouse_stock (
    id, warehouse_id, warehouse_code, warehouse_name,
    product_id, product_code, product_name, unit_name,
    stock_qty, locked_qty, create_time, update_time, version
) VALUES
(1940000000000000001, 1930000000000000001, 'WH001', '华东中心仓', 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 8600, 1800, '2026-06-14 09:20:00', '2026-06-14 09:20:00', 0),
(1940000000000000002, 1930000000000000001, 'WH001', '华东中心仓', 1920000000000000021, 'P000021', '中性签字笔', '盒', 4200, 1200, '2026-06-14 09:15:00', '2026-06-14 09:15:00', 0),
(1940000000000000003, 1930000000000000002, 'WH002', '华南中心仓', 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 5400, 0, '2026-06-14 08:50:00', '2026-06-14 08:50:00', 0),
(1940000000000000004, 1930000000000000002, 'WH002', '华南中心仓', 1920000000000000026, 'P000026', 'A4复印纸', '箱', 1300, 1000, '2026-06-14 08:45:00', '2026-06-14 08:45:00', 0),
(1940000000000000005, 1930000000000000002, 'WH002', '华南中心仓', 1920000000000000027, 'P000027', '热敏标签纸', '卷', 0, 0, '2026-06-14 08:40:00', '2026-06-14 08:40:00', 0),
(1940000000000000006, 1930000000000000003, 'WH003', '华北中心仓', 1920000000000000033, 'P000033', '浓缩洗衣液', '瓶', 900, 0, '2026-06-13 17:25:00', '2026-06-13 17:25:00', 0),
(1940000000000000007, 1930000000000000004, 'WH004', '西南中心仓', 1920000000000000034, 'P000034', '厨房清洁湿巾', '包', 4800, 1600, '2026-06-13 16:48:00', '2026-06-13 16:48:00', 0),
(1940000000000000008, 1930000000000000004, 'WH004', '西南中心仓', 1920000000000000037, 'P000037', '加厚垃圾袋', '卷', 2500, 500, '2026-06-13 16:45:00', '2026-06-13 16:45:00', 0),
(1940000000000000009, 1930000000000000005, 'WH005', '武汉中转仓', 1920000000000000008, 'P000008', '海盐苏打饼干', '箱', 1900, 0, '2026-06-13 15:20:00', '2026-06-13 15:20:00', 0),
(1940000000000000010, 1930000000000000005, 'WH005', '武汉中转仓', 1920000000000000038, 'P000038', '无痕粘钩', '卡', 1100, 100, '2026-06-13 15:18:00', '2026-06-13 15:18:00', 0),
(1940000000000000011, 1930000000000000006, 'WH006', '西安中转仓', 1920000000000000022, 'P000022', '彩色便利贴', '本', 6300, 0, '2026-06-13 14:35:00', '2026-06-13 14:35:00', 0),
(1940000000000000012, 1930000000000000007, 'WH007', '杭州电商仓', 1920000000000000044, 'P000044', '无线办公鼠标', '个', 800, 800, '2026-06-13 13:10:00', '2026-06-13 13:10:00', 0),
(1940000000000000013, 1930000000000000008, 'WH008', '南京备货仓', 1920000000000000043, 'P000043', 'USB-C扩展坞', '个', 400, 400, '2026-06-13 11:55:00', '2026-06-13 11:55:00', 0),
(1940000000000000014, 1930000000000000003, 'WH003', '华北中心仓', 1920000000000000008, 'P000008', '海盐苏打饼干', '箱', 2400, 400, '2026-07-01 09:15:00', '2026-07-01 09:15:00', 0),
(1940000000000000015, 1930000000000000006, 'WH006', '西安中转仓', 1920000000000000026, 'P000026', 'A4复印纸', '箱', 1800, 400, '2026-07-02 14:20:00', '2026-07-02 14:20:00', 0),
(1940000000000000016, 1930000000000000008, 'WH008', '南京备货仓', 1920000000000000012, 'P000012', '东北长粒香大米', 'kg', 2550, 0, '2026-07-03 10:30:00', '2026-07-03 10:30:00', 0),
(1940000000000000017, 1930000000000000011, 'WH011', '郑州临时仓', 1920000000000000034, 'P000034', '厨房清洁湿巾', '包', 2700, 0, '2026-07-04 11:10:00', '2026-07-04 11:10:00', 0),
(1940000000000000018, 1930000000000000012, 'WH012', '合肥样品仓', 1920000000000000037, 'P000037', '加厚垃圾袋', '卷', 1600, 200, '2026-07-05 16:45:00', '2026-07-05 16:45:00', 0)
ON DUPLICATE KEY UPDATE warehouse_id = VALUES(warehouse_id), warehouse_code = VALUES(warehouse_code),
warehouse_name = VALUES(warehouse_name), product_id = VALUES(product_id), product_code = VALUES(product_code),
product_name = VALUES(product_name), unit_name = VALUES(unit_name), stock_qty = VALUES(stock_qty),
locked_qty = VALUES(locked_qty), create_time = VALUES(create_time), update_time = VALUES(update_time), version = VALUES(version);

INSERT IGNORE INTO inbound_bill (
    id, inbound_no, inbound_type, source_type, source_id, source_no,
    source_party_id, source_party_name, entry_mode,
    warehouse_id, warehouse_name, status,
    confirmed_by_id, confirmed_by_name, confirmed_at,
    created_by_id, created_by_name, responsible_by_id, responsible_by_name,
    create_time, update_time, manual_reason, remark, version
) VALUES
(1932000000000000001, 'IB2026070100001', 'ADJUST_IN', 'STOCK_ADJUST', NULL, 'ADJ202607010001', 1930000000000000003, '华北中心仓', 'MANUAL_ADJUSTMENT', 1930000000000000003, '华北中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-07-01 09:12:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-07-01 08:45:00', '2026-07-01 09:12:00', '月末盘点发现库存盘盈', '盘盈16盒每日坚果混合装', 0);

INSERT INTO inbound_bill (
    id, inbound_no, inbound_type, source_type, source_id, source_no,
    source_party_id, source_party_name, entry_mode,
    warehouse_id, warehouse_name, status,
    confirmed_by_id, confirmed_by_name, confirmed_at,
    created_by_id, created_by_name, responsible_by_id, responsible_by_name,
    create_time, update_time, manual_reason, remark, version
) VALUES
(1950000000000000001, 'IB2026061000001', 'PURCHASE_IN', 'PURCHASE_ORDER', NULL, 'PO202606001', NULL, '华东饮品供应链', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-06-14 09:12:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-14 09:12:00', '2026-06-14 09:12:00', '', '', 0),
(1950000000000000003, 'IB2026061100001', 'ADJUST_IN', 'STOCK_ADJUST', NULL, 'ADJ202606001', 1930000000000000002, '华南中心仓', 'MANUAL_ADJUSTMENT', 1930000000000000002, '华南中心仓', 'DRAFT', NULL, '', NULL, 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-14 10:30:00', '2026-06-14 10:30:00', '库存盘点调整', '', 0),
(1950000000000000004, 'IB2026061200009', 'SALES_RETURN', 'SALES_RETURN_ORDER', NULL, 'SRO202606001', NULL, '广州天河门店', 'SOURCE_GENERATED', 1930000000000000002, '华南中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-06-14 11:15:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-14 11:15:00', '2026-06-14 11:15:00', '', '', 0),
(1950000000000000006, 'IB2026061200010', 'PURCHASE_IN', 'PURCHASE_ORDER', NULL, 'PO202606002', NULL, '谷仓食品批发', 'SOURCE_GENERATED', 1930000000000000003, '华北中心仓', 'PENDING_CONFIRM', NULL, '', NULL, 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-13 15:28:00', '2026-06-13 15:28:00', '', '', 0),
(1950000000000000009, 'IB2026061300006', 'PURCHASE_IN', 'PURCHASE_ORDER', NULL, 'PO202606003', NULL, '文仪办公渠道', 'SOURCE_GENERATED', 1930000000000000005, '武汉中转仓', 'PENDING_CONFIRM', NULL, '', NULL, 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-12 17:36:00', '2026-06-12 17:36:00', '', '', 0),
(1950000000000000010, 'IB2026061400001', 'SALES_RETURN', 'SALES_RETURN_ORDER', NULL, 'SRO202606002', NULL, '武汉江岸客户', 'SOURCE_GENERATED', 1930000000000000005, '武汉中转仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-06-12 16:18:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-12 16:18:00', '2026-06-12 16:18:00', '', '', 0),
(1950000000000000013, 'IB2026061400003', 'ADJUST_IN', 'STOCK_ADJUST', NULL, 'ADJ202606003', 1930000000000000008, '南京备货仓', 'MANUAL_ADJUSTMENT', 1930000000000000008, '南京备货仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-06-11 15:32:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-11 15:32:00', '2026-06-11 15:32:00', '库存盘点调整', '', 0),
(1950000000000000014, 'IB2026061400004', 'PURCHASE_IN', 'PURCHASE_ORDER', NULL, 'PO202606004', NULL, '文仪办公渠道', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'CANCELLED', NULL, '', NULL, 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-10 11:25:00', '2026-06-10 11:25:00', '', '业务单据取消，库存未发生变化', 0)
ON DUPLICATE KEY UPDATE inbound_no = VALUES(inbound_no), inbound_type = VALUES(inbound_type),
source_type = VALUES(source_type), source_id = VALUES(source_id), source_no = VALUES(source_no),
source_party_id = VALUES(source_party_id), source_party_name = VALUES(source_party_name), entry_mode = VALUES(entry_mode),
warehouse_id = VALUES(warehouse_id), warehouse_name = VALUES(warehouse_name), status = VALUES(status),
confirmed_by_id = VALUES(confirmed_by_id),
confirmed_by_name = VALUES(confirmed_by_name), confirmed_at = VALUES(confirmed_at), created_by_id = VALUES(created_by_id),
created_by_name = VALUES(created_by_name), responsible_by_id = VALUES(responsible_by_id),
responsible_by_name = VALUES(responsible_by_name), create_time = VALUES(create_time), update_time = VALUES(update_time),
manual_reason = VALUES(manual_reason), remark = VALUES(remark), version = VALUES(version);

INSERT IGNORE INTO inbound_bill_item (
    id, inbound_bill_id, inbound_no, source_item_id,
    product_id, product_code, product_name, unit_name, quantity_precision,
    plan_qty, processed_qty, current_qty, pending_qty,
    qualified_qty, defective_qty, stock_bill_item_id,
    create_time, update_time, remark
) VALUES
(1932100000000000001, 1932000000000000001, 'IB2026070100001', NULL, 1920000000000000007, 'P000007', '每日坚果混合装', '盒', 0, 0, 0, 1600, 0, 0, 0, 1934100000000000001, '2026-07-01 08:45:00', '2026-07-16 18:18:51', '库存盘盈调整');

INSERT INTO inbound_bill_item (
    id, inbound_bill_id, inbound_no, source_item_id,
    product_id, product_code, product_name, unit_name, quantity_precision,
    plan_qty, processed_qty, current_qty, pending_qty,
    qualified_qty, defective_qty, stock_bill_item_id,
    create_time, update_time, remark
) VALUES
(1960000000000001001, 1950000000000000001, 'IB2026061000001', NULL, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 4800, 0, 3000, 1800, 2900, 100, 1991000000000000001, '2026-06-14 09:12:00', '2026-06-14 09:12:00', '含 1 个不合格品，已记录质检结果'),
(1960000000000001002, 1950000000000000001, 'IB2026061000001', NULL, 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 0, 2000, 0, 1200, 800, 1200, 0, 1991000000000000002, '2026-06-14 09:12:00', '2026-06-14 09:12:00', ''),
(1960000000000003001, 1950000000000000003, 'IB2026061100001', NULL, 1920000000000000026, 'P000026', 'A4复印纸', '箱', 0, 0, 0, 300, 0, 0, 0, NULL, '2026-06-14 10:30:00', '2026-06-14 10:30:00', ''),
(1960000000000004001, 1950000000000000004, 'IB2026061200009', NULL, 1920000000000000027, 'P000027', '热敏标签纸', '卷', 0, 1000, 0, 1000, 0, 900, 100, 1991000000000000004, '2026-06-14 11:15:00', '2026-06-14 11:15:00', '含 1 个不合格品，已记录质检结果'),
(1960000000000006001, 1950000000000000006, 'IB2026061200010', NULL, 1920000000000000007, 'P000007', '每日坚果混合装', '盒', 0, 6000, 2000, 0, 4000, 0, 0, NULL, '2026-06-13 15:28:00', '2026-06-13 15:28:00', ''),
(1960000000000006002, 1950000000000000006, 'IB2026061200010', NULL, 1920000000000000033, 'P000033', '浓缩洗衣液', '瓶', 0, 2700, 900, 0, 1800, 0, 0, NULL, '2026-06-13 15:28:00', '2026-06-13 15:28:00', ''),
(1960000000000009001, 1950000000000000009, 'IB2026061300006', NULL, 1920000000000000038, 'P000038', '无痕粘钩', '卡', 0, 4000, 0, 0, 4000, 0, 0, NULL, '2026-06-12 17:36:00', '2026-06-12 17:36:00', ''),
(1960000000000010001, 1950000000000000010, 'IB2026061400001', NULL, 1920000000000000008, 'P000008', '海盐苏打饼干', '箱', 0, 400, 0, 400, 0, 300, 100, 1991000000000000007, '2026-06-12 16:18:00', '2026-06-12 16:18:00', '含 1 个不合格品，已记录质检结果'),
(1960000000000013001, 1950000000000000013, 'IB2026061400003', NULL, 1920000000000000043, 'P000043', 'USB-C扩展坞', '个', 0, 0, 0, 200, 0, 0, 0, 1991000000000000010, '2026-06-11 15:32:00', '2026-06-11 15:32:00', ''),
(1960000000000014001, 1950000000000000014, 'IB2026061400004', NULL, 1920000000000000021, 'P000021', '中性签字笔', '盒', 0, 2000, 0, 2000, 0, 2000, 0, NULL, '2026-06-10 11:25:00', '2026-06-10 11:25:00', '')
ON DUPLICATE KEY UPDATE inbound_bill_id = VALUES(inbound_bill_id), inbound_no = VALUES(inbound_no),
source_item_id = VALUES(source_item_id), product_id = VALUES(product_id), product_code = VALUES(product_code),
product_name = VALUES(product_name), unit_name = VALUES(unit_name), quantity_precision = VALUES(quantity_precision),
plan_qty = VALUES(plan_qty), processed_qty = VALUES(processed_qty), current_qty = VALUES(current_qty),
pending_qty = VALUES(pending_qty), qualified_qty = VALUES(qualified_qty), defective_qty = VALUES(defective_qty),
stock_bill_item_id = VALUES(stock_bill_item_id), create_time = VALUES(create_time),
update_time = VALUES(update_time), remark = VALUES(remark);

INSERT IGNORE INTO outbound_bill (
    id, outbound_no, outbound_type, source_type, source_id, source_no,
    source_party_id, source_party_name, entry_mode,
    warehouse_id, warehouse_name, status,
    confirmed_by_id, confirmed_by_name, confirmed_at,
    created_by_id, created_by_name, responsible_by_id, responsible_by_name,
    create_time, update_time, manual_reason, remark, version
) VALUES
(1933000000000000001, 'OB2026070100001', 'ADJUST_OUT', 'STOCK_ADJUST', NULL, 'ADJ202607010002', 1930000000000000001, '华东中心仓', 'MANUAL_ADJUSTMENT', 1930000000000000001, '华东中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-07-01 10:05:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-07-01 09:30:00', '2026-07-01 10:05:00', '月末盘点发现库存盘亏', '盘亏10盒速溶黑咖啡', 0);

INSERT INTO outbound_bill (
    id, outbound_no, outbound_type, source_type, source_id, source_no,
    source_party_id, source_party_name, entry_mode,
    warehouse_id, warehouse_name, status,
    confirmed_by_id, confirmed_by_name, confirmed_at,
    created_by_id, created_by_name, responsible_by_id, responsible_by_name,
    create_time, update_time, manual_reason, remark, version
) VALUES
(1950000000000000002, 'OB2026061400002', 'SALES_OUT', 'SALES_ORDER', NULL, 'SO202606001', NULL, '上海星河便利店', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-06-14 10:05:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-14 10:05:00', '2026-06-14 10:05:00', '', '', 0),
(1950000000000000005, 'OB2026061300005', 'PURCHASE_RETURN', 'PURCHASE_RETURN_ORDER', NULL, 'PRO202606001', NULL, '谷仓食品批发', 'SOURCE_GENERATED', 1930000000000000003, '华北中心仓', 'CANCELLED', NULL, '', NULL, 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-13 16:42:00', '2026-06-13 16:42:00', '', '业务单据取消，库存未发生变化', 0),
(1950000000000000007, 'OB2026061300007', 'SALES_OUT', 'SALES_ORDER', NULL, 'SO202606002', NULL, '成都青柠商贸', 'SOURCE_GENERATED', 1930000000000000004, '西南中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-06-13 14:50:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-13 14:50:00', '2026-06-13 14:50:00', '', '', 0),
(1950000000000000008, 'OB2026061300008', 'ADJUST_OUT', 'STOCK_ADJUST', NULL, 'ADJ202606002', 1930000000000000004, '西南中心仓', 'MANUAL_ADJUSTMENT', 1930000000000000004, '西南中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-06-13 13:20:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-13 13:20:00', '2026-06-13 13:20:00', '库存盘点调整', '', 0),
(1950000000000000011, 'OB2026061200011', 'PURCHASE_RETURN', 'PURCHASE_RETURN_ORDER', NULL, 'PRO202606002', NULL, '森纸纸业集团', 'SOURCE_GENERATED', 1930000000000000006, '西安中转仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-06-12 14:45:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-12 14:45:00', '2026-06-12 14:45:00', '', '', 0),
(1950000000000000012, 'OB2026061100012', 'SALES_OUT', 'SALES_ORDER', NULL, 'SO202606003', NULL, '杭州电商客户', 'SOURCE_GENERATED', 1930000000000000007, '杭州电商仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-06-11 18:05:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-11 18:05:00', '2026-06-11 18:05:00', '', '', 0),
(1950000000000000015, 'OB2026061000015', 'SALES_OUT', 'SALES_ORDER', NULL, 'SO202606004', NULL, '广州天河门店', 'SOURCE_GENERATED', 1930000000000000002, '华南中心仓', 'PENDING_CONFIRM', NULL, '', NULL, 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-06-10 09:40:00', '2026-06-10 09:40:00', '', '', 0)
ON DUPLICATE KEY UPDATE outbound_no = VALUES(outbound_no), outbound_type = VALUES(outbound_type),
source_type = VALUES(source_type), source_id = VALUES(source_id), source_no = VALUES(source_no),
source_party_id = VALUES(source_party_id), source_party_name = VALUES(source_party_name), entry_mode = VALUES(entry_mode),
warehouse_id = VALUES(warehouse_id), warehouse_name = VALUES(warehouse_name), status = VALUES(status),
confirmed_by_id = VALUES(confirmed_by_id), confirmed_by_name = VALUES(confirmed_by_name),
confirmed_at = VALUES(confirmed_at), created_by_id = VALUES(created_by_id), created_by_name = VALUES(created_by_name),
responsible_by_id = VALUES(responsible_by_id), responsible_by_name = VALUES(responsible_by_name),
create_time = VALUES(create_time), update_time = VALUES(update_time), manual_reason = VALUES(manual_reason),
remark = VALUES(remark), version = VALUES(version);

INSERT IGNORE INTO outbound_bill_item (
    id, outbound_bill_id, outbound_no, source_item_id,
    product_id, product_code, product_name, unit_name, quantity_precision,
    plan_qty, processed_qty, current_qty, pending_qty, qualified_qty, defective_qty, stock_bill_item_id,
    create_time, update_time, remark
) VALUES
(1933100000000000001, 1933000000000000001, 'OB2026070100001', NULL, 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 0, 0, 0, 1000, 0, 0, 0, 1934100000000000002, '2026-07-01 09:30:00', '2026-07-16 18:18:51', '库存盘亏调整');

INSERT INTO outbound_bill_item (
    id, outbound_bill_id, outbound_no, source_item_id,
    product_id, product_code, product_name, unit_name, quantity_precision,
    plan_qty, processed_qty, current_qty, pending_qty, qualified_qty, defective_qty, stock_bill_item_id,
    create_time, update_time, remark
) VALUES
(1960000000000002001, 1950000000000000002, 'OB2026061400002', NULL, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 800, 0, 800, 0, 0, 0, 1991000000000000003, '2026-06-14 10:05:00', '2026-06-14 10:05:00', ''),
(1960000000000005001, 1950000000000000005, 'OB2026061300005', NULL, 1920000000000000007, 'P000007', '每日坚果混合装', '盒', 0, 600, 200, 200, 200, 0, 0, NULL, '2026-06-13 16:42:00', '2026-06-13 16:42:00', ''),
(1960000000000007001, 1950000000000000007, 'OB2026061300007', NULL, 1920000000000000034, 'P000034', '厨房清洁湿巾', '包', 0, 1200, 0, 1200, 0, 0, 0, 1991000000000000005, '2026-06-13 14:50:00', '2026-06-13 14:50:00', ''),
(1960000000000008001, 1950000000000000008, 'OB2026061300008', NULL, 1920000000000000037, 'P000037', '加厚垃圾袋', '卷', 0, 0, 0, 300, 0, 0, 0, 1991000000000000006, '2026-06-13 13:20:00', '2026-06-13 13:20:00', ''),
(1960000000000011001, 1950000000000000011, 'OB2026061200011', NULL, 1920000000000000022, 'P000022', '彩色便利贴', '本', 0, 700, 0, 700, 0, 0, 0, 1991000000000000008, '2026-06-12 14:45:00', '2026-06-12 14:45:00', ''),
(1960000000000012001, 1950000000000000012, 'OB2026061100012', NULL, 1920000000000000044, 'P000044', '无线办公鼠标', '个', 0, 600, 0, 600, 0, 0, 0, 1991000000000000009, '2026-06-11 18:05:00', '2026-06-11 18:05:00', ''),
(1960000000000015001, 1950000000000000015, 'OB2026061000015', NULL, 1920000000000000026, 'P000026', 'A4复印纸', '箱', 0, 1000, 0, 0, 1000, 0, 0, NULL, '2026-06-10 09:40:00', '2026-06-10 09:40:00', '')
ON DUPLICATE KEY UPDATE outbound_bill_id = VALUES(outbound_bill_id), outbound_no = VALUES(outbound_no),
source_item_id = VALUES(source_item_id), product_id = VALUES(product_id), product_code = VALUES(product_code),
product_name = VALUES(product_name), unit_name = VALUES(unit_name), quantity_precision = VALUES(quantity_precision),
plan_qty = VALUES(plan_qty), processed_qty = VALUES(processed_qty), current_qty = VALUES(current_qty),
pending_qty = VALUES(pending_qty), qualified_qty = VALUES(qualified_qty), defective_qty = VALUES(defective_qty),
stock_bill_item_id = VALUES(stock_bill_item_id),
create_time = VALUES(create_time), update_time = VALUES(update_time), remark = VALUES(remark);

INSERT IGNORE INTO stock_bill (
    id, bill_no, bill_type, work_bill_id,
    business_source_id, business_source_no, entry_mode,
    warehouse_id, warehouse_name,
    confirmed_by_id, confirmed_by_name, confirmed_at,
    create_time, remark
) VALUES
(1934000000000000001, 'SB2026070100001', 'ADJUST_IN', 1932000000000000001, NULL, 'ADJ202607010001', 'MANUAL_ADJUSTMENT', 1930000000000000003, '华北中心仓', 1900000000000000004, '仓管主管', '2026-07-01 09:12:00', '2026-07-01 09:12:00', '盘盈调整入库确认后自动生成'),
(1934000000000000002, 'SB2026070100002', 'ADJUST_OUT', 1933000000000000001, NULL, 'ADJ202607010002', 'MANUAL_ADJUSTMENT', 1930000000000000001, '华东中心仓', 1900000000000000004, '仓管主管', '2026-07-01 10:05:00', '2026-07-01 10:05:00', '盘亏调整出库确认后自动生成');

INSERT INTO stock_bill (
    id, bill_no, bill_type, work_bill_id,
    business_source_id, business_source_no, entry_mode,
    warehouse_id, warehouse_name,
    confirmed_by_id, confirmed_by_name, confirmed_at,
    create_time, remark
) VALUES
(1990000000000000001, 'SB2026061400001', 'PURCHASE_IN', 1950000000000000001, 2012000000000000101, 'PO202606001', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 1900000000000000004, '仓管主管', '2026-06-14 09:12:00', '2026-06-14 09:12:00', '由确认工作单自动生成'),
(1990000000000000002, 'SB2026061400002', 'SALES_OUT', 1950000000000000002, 2022000000000000101, 'SO202606001', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 1900000000000000004, '仓管主管', '2026-06-14 10:05:00', '2026-06-14 10:05:00', '由确认工作单自动生成'),
(1990000000000000003, 'SB2026061400004', 'SALES_RETURN', 1950000000000000004, NULL, 'SRO202606001', 'SOURCE_GENERATED', 1930000000000000002, '华南中心仓', 1900000000000000004, '仓管主管', '2026-06-14 11:15:00', '2026-06-14 11:15:00', '由确认工作单自动生成'),
(1990000000000000004, 'SB2026061300007', 'SALES_OUT', 1950000000000000007, 2022000000000000102, 'SO202606002', 'SOURCE_GENERATED', 1930000000000000004, '西南中心仓', 1900000000000000004, '仓管主管', '2026-06-13 14:50:00', '2026-06-13 14:50:00', '由确认工作单自动生成'),
(1990000000000000005, 'SB2026061300008', 'ADJUST_OUT', 1950000000000000008, NULL, 'ADJ202606002', 'MANUAL_ADJUSTMENT', 1930000000000000004, '西南中心仓', 1900000000000000004, '仓管主管', '2026-06-13 13:20:00', '2026-06-13 13:20:00', '由确认工作单自动生成'),
(1990000000000000006, 'SB2026061200010', 'SALES_RETURN', 1950000000000000010, NULL, 'SRO202606002', 'SOURCE_GENERATED', 1930000000000000005, '武汉中转仓', 1900000000000000004, '仓管主管', '2026-06-12 16:18:00', '2026-06-12 16:18:00', '由确认工作单自动生成'),
(1990000000000000007, 'SB2026061200011', 'PURCHASE_RETURN', 1950000000000000011, NULL, 'PRO202606002', 'SOURCE_GENERATED', 1930000000000000006, '西安中转仓', 1900000000000000004, '仓管主管', '2026-06-12 14:45:00', '2026-06-12 14:45:00', '由确认工作单自动生成'),
(1990000000000000008, 'SB2026061100012', 'SALES_OUT', 1950000000000000012, 2022000000000000103, 'SO202606003', 'SOURCE_GENERATED', 1930000000000000007, '杭州电商仓', 1900000000000000004, '仓管主管', '2026-06-11 18:05:00', '2026-06-11 18:05:00', '由确认工作单自动生成'),
(1990000000000000009, 'SB2026061100013', 'ADJUST_IN', 1950000000000000013, NULL, 'ADJ202606003', 'MANUAL_ADJUSTMENT', 1930000000000000008, '南京备货仓', 1900000000000000004, '仓管主管', '2026-06-11 15:32:00', '2026-06-11 15:32:00', '由确认工作单自动生成')
ON DUPLICATE KEY UPDATE bill_no = VALUES(bill_no), bill_type = VALUES(bill_type),
work_bill_id = VALUES(work_bill_id), business_source_id = VALUES(business_source_id),
business_source_no = VALUES(business_source_no), entry_mode = VALUES(entry_mode),
warehouse_id = VALUES(warehouse_id), warehouse_name = VALUES(warehouse_name),
confirmed_by_id = VALUES(confirmed_by_id), confirmed_by_name = VALUES(confirmed_by_name),
confirmed_at = VALUES(confirmed_at), create_time = VALUES(create_time), remark = VALUES(remark);

INSERT IGNORE INTO stock_bill_item (
    id, bill_id, work_bill_item_id, business_source_item_id,
    product_id, product_code, product_name, unit_name, quantity_precision,
    qualified_qty, defective_qty, before_qty, change_qty, after_qty,
    create_time, remark
) VALUES
(1934100000000000001, 1934000000000000001, 1932100000000000001, NULL, 1920000000000000007, 'P000007', '每日坚果混合装', '盒', 0, 0, 0, 1500, 1600, 3100, '2026-07-01 09:12:00', '盘盈16盒'),
(1934100000000000002, 1934000000000000002, 1933100000000000001, NULL, 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 0, 0, 0, 1700, -1000, 700, '2026-07-01 10:05:00', '盘亏10盒');

INSERT INTO stock_bill_item (
    id, bill_id, work_bill_item_id, business_source_item_id,
    product_id, product_code, product_name, unit_name, quantity_precision,
    qualified_qty, defective_qty, before_qty, change_qty, after_qty,
    create_time, remark
) VALUES
(1991000000000000001, 1990000000000000001, 1960000000000001001, NULL, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 2900, 100, 1200, 3000, 4200, '2026-06-14 09:12:00', '含 1 个不合格品，已记录质检结果'),
(1991000000000000002, 1990000000000000001, 1960000000000001002, NULL, 1920000000000000002, 'P000002', '速溶黑咖啡', '盒', 0, 1200, 0, 500, 1200, 1700, '2026-06-14 09:12:00', ''),
(1991000000000000003, 1990000000000000002, 1960000000000002001, NULL, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 0, 0, 9400, -800, 8600, '2026-06-14 10:05:00', ''),
(1991000000000000004, 1990000000000000003, 1960000000000004001, NULL, 1920000000000000027, 'P000027', '热敏标签纸', '卷', 0, 900, 100, 0, 1000, 1000, '2026-06-14 11:15:00', '含 1 个不合格品，已记录质检结果'),
(1991000000000000005, 1990000000000000004, 1960000000000007001, NULL, 1920000000000000034, 'P000034', '厨房清洁湿巾', '包', 0, 0, 0, 6000, -1200, 4800, '2026-06-13 14:50:00', ''),
(1991000000000000006, 1990000000000000005, 1960000000000008001, NULL, 1920000000000000037, 'P000037', '加厚垃圾袋', '卷', 0, 0, 0, 2800, -300, 2500, '2026-06-13 13:20:00', ''),
(1991000000000000007, 1990000000000000006, 1960000000000010001, NULL, 1920000000000000008, 'P000008', '海盐苏打饼干', '箱', 0, 300, 100, 1500, 400, 1900, '2026-06-12 16:18:00', '含 1 个不合格品，已记录质检结果'),
(1991000000000000008, 1990000000000000007, 1960000000000011001, NULL, 1920000000000000022, 'P000022', '彩色便利贴', '本', 0, 0, 0, 7000, -700, 6300, '2026-06-12 14:45:00', ''),
(1991000000000000009, 1990000000000000008, 1960000000000012001, NULL, 1920000000000000044, 'P000044', '无线办公鼠标', '个', 0, 0, 0, 1400, -600, 800, '2026-06-11 18:05:00', ''),
(1991000000000000010, 1990000000000000009, 1960000000000013001, NULL, 1920000000000000043, 'P000043', 'USB-C扩展坞', '个', 0, 0, 0, 1500, 200, 1700, '2026-06-11 15:32:00', '')
ON DUPLICATE KEY UPDATE bill_id = VALUES(bill_id), work_bill_item_id = VALUES(work_bill_item_id),
business_source_item_id = VALUES(business_source_item_id),
product_id = VALUES(product_id), product_code = VALUES(product_code), product_name = VALUES(product_name),
unit_name = VALUES(unit_name), quantity_precision = VALUES(quantity_precision),
qualified_qty = VALUES(qualified_qty), defective_qty = VALUES(defective_qty), before_qty = VALUES(before_qty),
change_qty = VALUES(change_qty), after_qty = VALUES(after_qty), create_time = VALUES(create_time), remark = VALUES(remark);

COMMIT;

-- ============================================================
-- 存量数据迁移：出入库单负责人字段改为可空
-- ============================================================
ALTER TABLE inbound_bill
  MODIFY COLUMN responsible_by_id BIGINT DEFAULT NULL COMMENT '业务负责人ID，确认时填入审核人',
  MODIFY COLUMN responsible_by_name VARCHAR(100) DEFAULT NULL COMMENT '业务负责人姓名快照，确认时填入审核人';
ALTER TABLE outbound_bill
  MODIFY COLUMN responsible_by_id BIGINT DEFAULT NULL COMMENT '业务负责人ID，确认时填入审核人',
  MODIFY COLUMN responsible_by_name VARCHAR(100) DEFAULT NULL COMMENT '业务负责人姓名快照，确认时填入审核人';
