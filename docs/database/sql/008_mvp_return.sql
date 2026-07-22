-- MVP 销售退货与采购退货库表设计
-- 数据库：MySQL 8
-- 说明：主键由 MyBatis-Plus ASSIGN_ID 生成，因此不使用 AUTO_INCREMENT。
-- 执行顺序：依赖 001～005 中的用户、产品、仓库、采购订单和销售订单种子，建议在这些脚本之后执行。

USE erp;

-- 建表前必须确认原订单累计履约数量不存在三、四位有效小数；以下两个结果均应为 0。
SELECT COUNT(*) AS invalid_purchase_inbound_qty_count
FROM purchase_order_item
WHERE inbound_qty <> ROUND(inbound_qty, 2);

SELECT COUNT(*) AS invalid_sales_outbound_qty_count
FROM sales_order_item
WHERE outbound_qty <> ROUND(outbound_qty, 2);

CREATE TABLE IF NOT EXISTS return_order (
    id BIGINT NOT NULL COMMENT '退货单ID',
    return_no VARCHAR(64) NOT NULL COMMENT '退货单号',
    return_type VARCHAR(32) NOT NULL COMMENT '退货类型：SALES_RETURN、PURCHASE_RETURN，创建后不可修改',
    source_order_id BIGINT NOT NULL COMMENT '原销售订单ID或采购订单ID',
    source_order_no VARCHAR(64) NOT NULL COMMENT '原订单号快照',
    party_id BIGINT NOT NULL COMMENT '销售退货为客户ID，采购退货为供应商ID',
    party_code VARCHAR(64) NOT NULL COMMENT '客户或供应商编码快照',
    party_name VARCHAR(200) NOT NULL COMMENT '客户或供应商名称快照',
    warehouse_id BIGINT NOT NULL COMMENT '退货执行仓库ID',
    warehouse_name VARCHAR(100) NOT NULL COMMENT '仓库名称快照',
    expected_execution_date DATE DEFAULT NULL COMMENT '预计退货执行日期；草稿可空，提交前必填',
    handling_type VARCHAR(32) NOT NULL DEFAULT 'REFUND' COMMENT '处理方式：REFUND、EXCHANGE、OTHER',
    reason_code VARCHAR(32) NOT NULL DEFAULT 'OTHER' COMMENT '退货原因编码',
    return_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '退货原因补充说明',
    total_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '当前有效退货总金额，由后端汇总明细',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT、SUBMITTED、APPROVED、PARTIAL_EXECUTED、COMPLETED、CANCELLED',
    status_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '最近一次审核退回或取消原因',
    created_by_id BIGINT DEFAULT NULL COMMENT '创建人ID',
    created_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '创建人姓名快照',
    submitted_at DATETIME DEFAULT NULL COMMENT '提交时间',
    approved_by_id BIGINT DEFAULT NULL COMMENT '审核人ID',
    approved_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '审核人姓名快照',
    approved_at DATETIME DEFAULT NULL COMMENT '审核时间',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_return_order_no (return_no),
    KEY idx_return_order_source (return_type, source_order_id),
    KEY idx_return_order_party (return_type, party_id),
    KEY idx_return_order_warehouse (warehouse_id),
    KEY idx_return_order_status (return_type, deleted, status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一退货单主表';

CREATE TABLE IF NOT EXISTS return_order_item (
    id BIGINT NOT NULL COMMENT '退货明细ID',
    return_order_id BIGINT NOT NULL COMMENT '退货单主表ID',
    source_order_item_id BIGINT NOT NULL COMMENT '原销售订单明细ID或采购订单明细ID',
    product_id BIGINT NOT NULL COMMENT '产品ID',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码快照',
    product_name VARCHAR(200) NOT NULL COMMENT '产品名称快照',
    unit_name VARCHAR(32) NOT NULL DEFAULT '件' COMMENT '单位名称快照',
    quantity_precision TINYINT NOT NULL DEFAULT 0 COMMENT '数量小数位快照：0-2',
    source_fulfilled_qty DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '创建退货明细时原订单累计已出库或已入库数量快照',
    requested_qty DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '申请退货数量',
    approved_qty DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '审核通过数量',
    processed_qty DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '仓库累计确认的实际处理总量',
    unit_price DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '原采购或销售订单明细单价快照',
    total_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '当前有效明细金额，由后端计算',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '明细备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_return_order_item_source (return_order_id, source_order_item_id),
    KEY idx_return_order_item_order (return_order_id),
    KEY idx_return_order_item_product (product_id),
    KEY idx_return_order_item_source_item (source_order_item_id),
    CONSTRAINT chk_return_order_item_quantity_precision CHECK (quantity_precision BETWEEN 0 AND 2),
    CONSTRAINT chk_return_order_item_source_fulfilled_qty CHECK (source_fulfilled_qty > 0),
    CONSTRAINT chk_return_order_item_requested_qty CHECK (requested_qty > 0 AND requested_qty <= source_fulfilled_qty),
    CONSTRAINT chk_return_order_item_approved_qty CHECK (approved_qty >= 0 AND approved_qty <= requested_qty),
    CONSTRAINT chk_return_order_item_processed_qty CHECK (processed_qty >= 0 AND processed_qty <= approved_qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='统一退货单明细表';

-- ============================================================
-- 退货模块种子数据
-- 采购退回统一引用 PO202606001 / 经典原味苏打水：累计已入库 48 箱，华东中心仓存在足够可用库存。
-- 销售退货统一引用 SO202606001 / 经典原味苏打水：累计已出库 8 箱。
-- DRAFT、CANCELLED 不占用可退数量；其余种子的累计占用不超过来源履约数量。
-- ============================================================

INSERT INTO return_order (
    id, return_no, return_type, source_order_id, source_order_no,
    party_id, party_code, party_name, warehouse_id, warehouse_name,
    expected_execution_date, handling_type, reason_code, return_reason, total_amount,
    status, status_reason, created_by_id, created_by_name, submitted_at,
    approved_by_id, approved_by_name, approved_at, create_time, update_time,
    deleted, remark, version
) VALUES
(2030000000000000001, 'PR202607001', 'PURCHASE_RETURN', 2012000000000000101, 'PO202606001', 2010000000000000001, 'S001', '华东饮品供应链', 1930000000000000001, '华东中心仓', '2026-07-28', 'REFUND', 'QUALITY_ISSUE', '到货抽检发现质量异常', 176.00, 'DRAFT', '', 1900000000000000002, '采购主管', NULL, NULL, '', NULL, '2026-07-10 09:30:00', '2026-07-10 09:30:00', 0, '采购退回草稿种子', 0),
(2030000000000000002, 'PR202607002', 'PURCHASE_RETURN', 2012000000000000101, 'PO202606001', 2010000000000000001, 'S001', '华东饮品供应链', 1930000000000000001, '华东中心仓', '2026-07-28', 'EXCHANGE', 'SPEC_MISMATCH', '规格与采购约定不一致', 140.80, 'SUBMITTED', '', 1900000000000000002, '采购主管', '2026-07-11 10:00:00', NULL, '', NULL, '2026-07-11 09:30:00', '2026-07-11 10:00:00', 0, '采购退回待审核种子', 1),
(2030000000000000003, 'PR202607003', 'PURCHASE_RETURN', 2012000000000000101, 'PO202606001', 2010000000000000001, 'S001', '华东饮品供应链', 1930000000000000001, '华东中心仓', '2026-07-28', 'REFUND', 'DAMAGED', '运输包装破损', 70.40, 'APPROVED', '', 1900000000000000002, '采购主管', '2026-07-12 09:40:00', 1900000000000000002, '采购主管', '2026-07-12 10:10:00', '2026-07-12 09:30:00', '2026-07-12 10:10:00', 0, '采购退回待出库种子', 2),
(2030000000000000004, 'PR202607004', 'PURCHASE_RETURN', 2012000000000000101, 'PO202606001', 2010000000000000001, 'S001', '华东饮品供应链', 1930000000000000001, '华东中心仓', '2026-07-28', 'REFUND', 'QUANTITY_ERROR', '到货数量与单据不符', 140.80, 'PARTIAL_EXECUTED', '', 1900000000000000002, '采购主管', '2026-07-13 09:40:00', 1900000000000000002, '采购主管', '2026-07-13 10:10:00', '2026-07-13 09:30:00', '2026-07-14 11:00:00', 0, '采购退回部分出库种子', 3),
(2030000000000000005, 'PR202607005', 'PURCHASE_RETURN', 2012000000000000101, 'PO202606001', 2010000000000000001, 'S001', '华东饮品供应链', 1930000000000000001, '华东中心仓', '2026-07-28', 'REFUND', 'QUALITY_ISSUE', '供应商确认接收退货', 70.40, 'COMPLETED', '', 1900000000000000002, '采购主管', '2026-07-14 09:40:00', 1900000000000000002, '采购主管', '2026-07-14 10:10:00', '2026-07-14 09:30:00', '2026-07-15 16:30:00', 0, '采购退回已完成种子', 4),
(2030000000000000006, 'PR202607006', 'PURCHASE_RETURN', 2012000000000000101, 'PO202606001', 2010000000000000001, 'S001', '华东饮品供应链', 1930000000000000001, '华东中心仓', '2026-07-28', 'OTHER', 'OTHER', '供应商已补发，无需继续退回', 70.40, 'CANCELLED', '供应商已补发，无需继续退回', 1900000000000000002, '采购主管', NULL, NULL, '', NULL, '2026-07-15 09:30:00', '2026-07-15 10:00:00', 0, '采购退回已取消种子', 1),
(2040000000000000001, 'SR202607001', 'SALES_RETURN', 2022000000000000101, 'SO202606001', 2020000000000000101, 'C006', '上海星河便利店', 1930000000000000001, '华东中心仓', '2026-07-28', 'REFUND', 'QUALITY_ISSUE', '客户反馈商品质量异常', 99.80, 'DRAFT', '', 1900000000000000003, '销售主管', NULL, NULL, '', NULL, '2026-07-10 14:30:00', '2026-07-10 14:30:00', 0, '销售退货草稿种子', 0),
(2040000000000000002, 'SR202607002', 'SALES_RETURN', 2022000000000000101, 'SO202606001', 2020000000000000101, 'C006', '上海星河便利店', 1930000000000000001, '华东中心仓', '2026-07-28', 'EXCHANGE', 'DAMAGED', '客户签收时发现外包装破损', 99.80, 'SUBMITTED', '', 1900000000000000003, '销售主管', '2026-07-11 15:00:00', NULL, '', NULL, '2026-07-11 14:30:00', '2026-07-11 15:00:00', 0, '销售退货待审核种子', 1),
(2040000000000000003, 'SR202607003', 'SALES_RETURN', 2022000000000000101, 'SO202606001', 2020000000000000101, 'C006', '上海星河便利店', 1930000000000000001, '华东中心仓', '2026-07-28', 'REFUND', 'WRONG_ITEM', '客户反馈商品错发', 99.80, 'APPROVED', '', 1900000000000000003, '销售主管', '2026-07-12 14:40:00', 1900000000000000003, '销售主管', '2026-07-12 15:10:00', '2026-07-12 14:30:00', '2026-07-12 15:10:00', 0, '销售退货待入库种子', 2),
(2040000000000000004, 'SR202607004', 'SALES_RETURN', 2022000000000000101, 'SO202606001', 2020000000000000101, 'C006', '上海星河便利店', 1930000000000000001, '华东中心仓', '2026-07-28', 'REFUND', 'QUANTITY_ERROR', '客户反馈到货数量有误', 99.80, 'PARTIAL_EXECUTED', '', 1900000000000000003, '销售主管', '2026-07-13 14:40:00', 1900000000000000003, '销售主管', '2026-07-13 15:10:00', '2026-07-13 14:30:00', '2026-07-14 16:00:00', 0, '销售退货部分入库种子', 3),
(2040000000000000005, 'SR202607005', 'SALES_RETURN', 2022000000000000101, 'SO202606001', 2020000000000000101, 'C006', '上海星河便利店', 1930000000000000001, '华东中心仓', '2026-07-28', 'REFUND', 'NO_LONGER_NEEDED', '客户不再需要该商品', 49.90, 'COMPLETED', '', 1900000000000000003, '销售主管', '2026-07-14 14:40:00', 1900000000000000003, '销售主管', '2026-07-14 15:10:00', '2026-07-14 14:30:00', '2026-07-15 17:30:00', 0, '销售退货已完成种子', 4),
(2040000000000000006, 'SR202607006', 'SALES_RETURN', 2022000000000000101, 'SO202606001', 2020000000000000101, 'C006', '上海星河便利店', 1930000000000000001, '华东中心仓', '2026-07-28', 'OTHER', 'OTHER', '客户取消退货申请', 49.90, 'CANCELLED', '客户取消退货申请', 1900000000000000003, '销售主管', NULL, NULL, '', NULL, '2026-07-15 14:30:00', '2026-07-15 15:00:00', 0, '销售退货已取消种子', 1)
ON DUPLICATE KEY UPDATE return_no = VALUES(return_no), return_type = VALUES(return_type), source_order_id = VALUES(source_order_id), source_order_no = VALUES(source_order_no),
party_id = VALUES(party_id), party_code = VALUES(party_code), party_name = VALUES(party_name), warehouse_id = VALUES(warehouse_id), warehouse_name = VALUES(warehouse_name),
expected_execution_date = VALUES(expected_execution_date), handling_type = VALUES(handling_type), reason_code = VALUES(reason_code), return_reason = VALUES(return_reason),
total_amount = VALUES(total_amount), status = VALUES(status), status_reason = VALUES(status_reason), created_by_id = VALUES(created_by_id), created_by_name = VALUES(created_by_name),
submitted_at = VALUES(submitted_at), approved_by_id = VALUES(approved_by_id), approved_by_name = VALUES(approved_by_name), approved_at = VALUES(approved_at),
create_time = VALUES(create_time), update_time = VALUES(update_time), deleted = VALUES(deleted), remark = VALUES(remark), version = VALUES(version);

INSERT INTO return_order_item (
    id, return_order_id, source_order_item_id, product_id, product_code, product_name,
    unit_name, quantity_precision, source_fulfilled_qty, requested_qty, approved_qty,
    processed_qty, unit_price, total_amount, create_time, update_time, remark
) VALUES
(2031000000000000001, 2030000000000000001, 2012100000000000101, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 48.00, 5.00, 0.00, 0.00, 35.20, 176.00, '2026-07-10 09:30:00', '2026-07-10 09:30:00', '供应商已确认接收，待提交审核'),
(2031000000000000002, 2030000000000000002, 2012100000000000101, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 48.00, 4.00, 0.00, 0.00, 35.20, 140.80, '2026-07-11 09:30:00', '2026-07-11 10:00:00', ''),
(2031000000000000003, 2030000000000000003, 2012100000000000101, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 48.00, 3.00, 2.00, 0.00, 35.20, 70.40, '2026-07-12 09:30:00', '2026-07-12 10:10:00', ''),
(2031000000000000004, 2030000000000000004, 2012100000000000101, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 48.00, 4.00, 4.00, 2.00, 35.20, 140.80, '2026-07-13 09:30:00', '2026-07-14 11:00:00', '仓库已完成部分退货出库'),
(2031000000000000005, 2030000000000000005, 2012100000000000101, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 48.00, 2.00, 2.00, 2.00, 35.20, 70.40, '2026-07-14 09:30:00', '2026-07-15 16:30:00', '仓库已完成全部退货出库'),
(2031000000000000006, 2030000000000000006, 2012100000000000101, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 48.00, 2.00, 0.00, 0.00, 35.20, 70.40, '2026-07-15 09:30:00', '2026-07-15 10:00:00', ''),
(2041000000000000001, 2040000000000000001, 2022100000000000101, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 8.00, 2.00, 0.00, 0.00, 49.90, 99.80, '2026-07-10 14:30:00', '2026-07-10 14:30:00', '客户已提供退货照片'),
(2041000000000000002, 2040000000000000002, 2022100000000000101, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 8.00, 2.00, 0.00, 0.00, 49.90, 99.80, '2026-07-11 14:30:00', '2026-07-11 15:00:00', ''),
(2041000000000000003, 2040000000000000003, 2022100000000000101, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 8.00, 2.00, 2.00, 0.00, 49.90, 99.80, '2026-07-12 14:30:00', '2026-07-12 15:10:00', ''),
(2041000000000000004, 2040000000000000004, 2022100000000000101, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 8.00, 2.00, 2.00, 1.00, 49.90, 99.80, '2026-07-13 14:30:00', '2026-07-14 16:00:00', '仓库已完成部分退货入库'),
(2041000000000000005, 2040000000000000005, 2022100000000000101, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 8.00, 1.00, 1.00, 1.00, 49.90, 49.90, '2026-07-14 14:30:00', '2026-07-15 17:30:00', '仓库已完成全部退货入库'),
(2041000000000000006, 2040000000000000006, 2022100000000000101, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 8.00, 1.00, 0.00, 0.00, 49.90, 49.90, '2026-07-15 14:30:00', '2026-07-15 15:00:00', '')
ON DUPLICATE KEY UPDATE return_order_id = VALUES(return_order_id), source_order_item_id = VALUES(source_order_item_id), product_id = VALUES(product_id),
product_code = VALUES(product_code), product_name = VALUES(product_name), unit_name = VALUES(unit_name), quantity_precision = VALUES(quantity_precision),
source_fulfilled_qty = VALUES(source_fulfilled_qty), requested_qty = VALUES(requested_qty), approved_qty = VALUES(approved_qty), processed_qty = VALUES(processed_qty),
unit_price = VALUES(unit_price), total_amount = VALUES(total_amount), create_time = VALUES(create_time), update_time = VALUES(update_time), remark = VALUES(remark);

-- ============================================================
-- 仓库执行事实种子
-- APPROVED 对应待确认工作单；PARTIAL_EXECUTED、COMPLETED 对应已确认工作单和库存流水。
-- 仓库工作单数量按 100 倍整数存储；销售退货种子均为合格品入库。
-- ============================================================

INSERT INTO outbound_bill (
    id, outbound_no, outbound_type, source_type, source_id, source_no,
    source_party_id, source_party_name, entry_mode,
    warehouse_id, warehouse_name, status,
    confirmed_by_id, confirmed_by_name, confirmed_at,
    created_by_id, created_by_name, responsible_by_id, responsible_by_name,
    create_time, update_time, manual_reason, remark, version
) VALUES
(2050000000000000001, 'OB202607120001', 'PURCHASE_RETURN', 'PURCHASE_RETURN_ORDER', 2030000000000000003, 'PR202607003', 2010000000000000001, '华东饮品供应链', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'PENDING_CONFIRM', NULL, '', NULL, 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-07-12 10:15:00', '2026-07-12 10:15:00', '', '采购退回审核后生成，待仓库确认', 0),
(2050000000000000002, 'OB202607140001', 'PURCHASE_RETURN', 'PURCHASE_RETURN_ORDER', 2030000000000000004, 'PR202607004', 2010000000000000001, '华东饮品供应链', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-07-14 11:00:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-07-14 10:30:00', '2026-07-14 11:00:00', '', '采购退回部分出库确认', 1),
(2050000000000000003, 'OB202607150001', 'PURCHASE_RETURN', 'PURCHASE_RETURN_ORDER', 2030000000000000005, 'PR202607005', 2010000000000000001, '华东饮品供应链', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-07-15 16:30:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-07-15 16:00:00', '2026-07-15 16:30:00', '', '采购退回全部出库确认', 1)
ON DUPLICATE KEY UPDATE outbound_no = VALUES(outbound_no), outbound_type = VALUES(outbound_type),
source_type = VALUES(source_type), source_id = VALUES(source_id), source_no = VALUES(source_no),
source_party_id = VALUES(source_party_id), source_party_name = VALUES(source_party_name), entry_mode = VALUES(entry_mode),
warehouse_id = VALUES(warehouse_id), warehouse_name = VALUES(warehouse_name), status = VALUES(status),
confirmed_by_id = VALUES(confirmed_by_id), confirmed_by_name = VALUES(confirmed_by_name), confirmed_at = VALUES(confirmed_at),
created_by_id = VALUES(created_by_id), created_by_name = VALUES(created_by_name), responsible_by_id = VALUES(responsible_by_id),
responsible_by_name = VALUES(responsible_by_name), create_time = VALUES(create_time), update_time = VALUES(update_time),
manual_reason = VALUES(manual_reason), remark = VALUES(remark), version = VALUES(version);

INSERT INTO outbound_bill_item (
    id, outbound_bill_id, outbound_no, source_item_id,
    product_id, product_code, product_name, unit_name, quantity_precision,
    plan_qty, processed_qty, current_qty, pending_qty, stock_bill_item_id,
    create_time, update_time, remark
) VALUES
(2051000000000000001, 2050000000000000001, 'OB202607120001', 2031000000000000003, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 200, 0, 200, 0, NULL, '2026-07-12 10:15:00', '2026-07-12 10:15:00', '待确认出库 2 箱'),
(2051000000000000002, 2050000000000000002, 'OB202607140001', 2031000000000000004, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 400, 0, 200, 200, 2071000000000000001, '2026-07-14 10:30:00', '2026-07-14 11:00:00', '本次出库 2 箱，剩余 2 箱'),
(2051000000000000003, 2050000000000000003, 'OB202607150001', 2031000000000000005, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 200, 0, 200, 0, 2071000000000000002, '2026-07-15 16:00:00', '2026-07-15 16:30:00', '本次出库 2 箱，全部完成')
ON DUPLICATE KEY UPDATE outbound_bill_id = VALUES(outbound_bill_id), outbound_no = VALUES(outbound_no),
source_item_id = VALUES(source_item_id), product_id = VALUES(product_id), product_code = VALUES(product_code),
product_name = VALUES(product_name), unit_name = VALUES(unit_name), quantity_precision = VALUES(quantity_precision),
plan_qty = VALUES(plan_qty), processed_qty = VALUES(processed_qty), current_qty = VALUES(current_qty),
pending_qty = VALUES(pending_qty), stock_bill_item_id = VALUES(stock_bill_item_id),
create_time = VALUES(create_time), update_time = VALUES(update_time), remark = VALUES(remark);

INSERT INTO inbound_bill (
    id, inbound_no, inbound_type, source_type, source_id, source_no,
    source_party_id, source_party_name, entry_mode,
    warehouse_id, warehouse_name, status, expected_arrival_date,
    confirmed_by_id, confirmed_by_name, confirmed_at,
    created_by_id, created_by_name, responsible_by_id, responsible_by_name,
    create_time, update_time, manual_reason, remark, version
) VALUES
(2060000000000000001, 'IB202607120001', 'SALES_RETURN', 'SALES_RETURN_ORDER', 2040000000000000003, 'SR202607003', 2020000000000000101, '上海星河便利店', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'PENDING_CONFIRM', '2026-07-28', NULL, '', NULL, 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-07-12 15:15:00', '2026-07-12 15:15:00', '', '销售退货审核后生成，待仓库确认', 0),
(2060000000000000002, 'IB202607140001', 'SALES_RETURN', 'SALES_RETURN_ORDER', 2040000000000000004, 'SR202607004', 2020000000000000101, '上海星河便利店', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'CONFIRMED', '2026-07-28', 1900000000000000004, '仓管主管', '2026-07-14 16:00:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-07-14 15:30:00', '2026-07-14 16:00:00', '', '销售退货部分入库确认', 1),
(2060000000000000003, 'IB202607150001', 'SALES_RETURN', 'SALES_RETURN_ORDER', 2040000000000000005, 'SR202607005', 2020000000000000101, '上海星河便利店', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'CONFIRMED', '2026-07-28', 1900000000000000004, '仓管主管', '2026-07-15 17:30:00', 1900000000000000004, '仓管主管', 1900000000000000004, '仓管主管', '2026-07-15 17:00:00', '2026-07-15 17:30:00', '', '销售退货全部入库确认', 1)
ON DUPLICATE KEY UPDATE inbound_no = VALUES(inbound_no), inbound_type = VALUES(inbound_type),
source_type = VALUES(source_type), source_id = VALUES(source_id), source_no = VALUES(source_no),
source_party_id = VALUES(source_party_id), source_party_name = VALUES(source_party_name), entry_mode = VALUES(entry_mode),
warehouse_id = VALUES(warehouse_id), warehouse_name = VALUES(warehouse_name), status = VALUES(status),
expected_arrival_date = VALUES(expected_arrival_date), confirmed_by_id = VALUES(confirmed_by_id),
confirmed_by_name = VALUES(confirmed_by_name), confirmed_at = VALUES(confirmed_at), created_by_id = VALUES(created_by_id),
created_by_name = VALUES(created_by_name), responsible_by_id = VALUES(responsible_by_id),
responsible_by_name = VALUES(responsible_by_name), create_time = VALUES(create_time), update_time = VALUES(update_time),
manual_reason = VALUES(manual_reason), remark = VALUES(remark), version = VALUES(version);

INSERT INTO inbound_bill_item (
    id, inbound_bill_id, inbound_no, source_item_id,
    product_id, product_code, product_name, unit_name, quantity_precision,
    plan_qty, processed_qty, current_qty, pending_qty,
    qualified_qty, defective_qty, stock_bill_item_id,
    create_time, update_time, remark
) VALUES
(2061000000000000001, 2060000000000000001, 'IB202607120001', 2041000000000000003, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 200, 0, 200, 0, 200, 0, NULL, '2026-07-12 15:15:00', '2026-07-12 15:15:00', '待确认入库 2 箱'),
(2061000000000000002, 2060000000000000002, 'IB202607140001', 2041000000000000004, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 200, 0, 100, 100, 100, 0, 2071000000000000003, '2026-07-14 15:30:00', '2026-07-14 16:00:00', '本次合格入库 1 箱，剩余 1 箱'),
(2061000000000000003, 2060000000000000003, 'IB202607150001', 2041000000000000005, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 100, 0, 100, 0, 100, 0, 2071000000000000004, '2026-07-15 17:00:00', '2026-07-15 17:30:00', '本次合格入库 1 箱，全部完成')
ON DUPLICATE KEY UPDATE inbound_bill_id = VALUES(inbound_bill_id), inbound_no = VALUES(inbound_no),
source_item_id = VALUES(source_item_id), product_id = VALUES(product_id), product_code = VALUES(product_code),
product_name = VALUES(product_name), unit_name = VALUES(unit_name), quantity_precision = VALUES(quantity_precision),
plan_qty = VALUES(plan_qty), processed_qty = VALUES(processed_qty), current_qty = VALUES(current_qty),
pending_qty = VALUES(pending_qty), qualified_qty = VALUES(qualified_qty), defective_qty = VALUES(defective_qty),
stock_bill_item_id = VALUES(stock_bill_item_id), create_time = VALUES(create_time),
update_time = VALUES(update_time), remark = VALUES(remark);

INSERT INTO stock_bill (
    id, bill_no, bill_type, direction,
    source_bill_type, source_bill_id, source_bill_no,
    business_source_type, business_source_id, business_source_no, entry_mode,
    warehouse_id, warehouse_name, status,
    confirmed_by_id, confirmed_by_name, confirmed_at,
    create_time, update_time, remark, version
) VALUES
(2070000000000000001, 'SB202607140001', 'PURCHASE_RETURN', 'OUTBOUND', 'OUTBOUND_BILL', 2050000000000000002, 'OB202607140001', 'PURCHASE_RETURN_ORDER', 2030000000000000004, 'PR202607004', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-07-14 11:00:00', '2026-07-14 11:00:00', '2026-07-14 11:00:00', '采购退回出库确认后自动生成', 0),
(2070000000000000002, 'SB202607150001', 'PURCHASE_RETURN', 'OUTBOUND', 'OUTBOUND_BILL', 2050000000000000003, 'OB202607150001', 'PURCHASE_RETURN_ORDER', 2030000000000000005, 'PR202607005', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-07-15 16:30:00', '2026-07-15 16:30:00', '2026-07-15 16:30:00', '采购退回出库确认后自动生成', 0),
(2070000000000000003, 'SB202607140002', 'SALES_RETURN', 'INBOUND', 'INBOUND_BILL', 2060000000000000002, 'IB202607140001', 'SALES_RETURN_ORDER', 2040000000000000004, 'SR202607004', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-07-14 16:00:00', '2026-07-14 16:00:00', '2026-07-14 16:00:00', '销售退货入库确认后自动生成', 0),
(2070000000000000004, 'SB202607150002', 'SALES_RETURN', 'INBOUND', 'INBOUND_BILL', 2060000000000000003, 'IB202607150001', 'SALES_RETURN_ORDER', 2040000000000000005, 'SR202607005', 'SOURCE_GENERATED', 1930000000000000001, '华东中心仓', 'CONFIRMED', 1900000000000000004, '仓管主管', '2026-07-15 17:30:00', '2026-07-15 17:30:00', '2026-07-15 17:30:00', '销售退货入库确认后自动生成', 0)
ON DUPLICATE KEY UPDATE bill_no = VALUES(bill_no), bill_type = VALUES(bill_type),
direction = VALUES(direction), source_bill_type = VALUES(source_bill_type), source_bill_id = VALUES(source_bill_id),
source_bill_no = VALUES(source_bill_no), business_source_type = VALUES(business_source_type),
business_source_id = VALUES(business_source_id), business_source_no = VALUES(business_source_no), entry_mode = VALUES(entry_mode),
warehouse_id = VALUES(warehouse_id), warehouse_name = VALUES(warehouse_name), status = VALUES(status),
confirmed_by_id = VALUES(confirmed_by_id), confirmed_by_name = VALUES(confirmed_by_name), confirmed_at = VALUES(confirmed_at),
create_time = VALUES(create_time), update_time = VALUES(update_time), remark = VALUES(remark), version = VALUES(version);

INSERT INTO stock_bill_item (
    id, bill_id, bill_no, source_bill_item_id, business_source_item_id,
    product_id, product_code, product_name, unit_name, quantity_precision,
    quantity, qualified_qty, defective_qty, before_qty, change_qty, after_qty,
    create_time, update_time, remark
) VALUES
(2071000000000000001, 2070000000000000001, 'SB202607140001', 2051000000000000002, 2031000000000000004, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 200, 0, 0, 8600, -200, 8400, '2026-07-14 11:00:00', '2026-07-14 11:00:00', '采购退回出库 2 箱'),
(2071000000000000002, 2070000000000000002, 'SB202607150001', 2051000000000000003, 2031000000000000005, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 200, 0, 0, 8500, -200, 8300, '2026-07-15 16:30:00', '2026-07-15 16:30:00', '采购退回出库 2 箱'),
(2071000000000000003, 2070000000000000003, 'SB202607140002', 2061000000000000002, 2041000000000000004, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 100, 100, 0, 8400, 100, 8500, '2026-07-14 16:00:00', '2026-07-14 16:00:00', '销售退货合格入库 1 箱'),
(2071000000000000004, 2070000000000000004, 'SB202607150002', 2061000000000000003, 2041000000000000005, 1920000000000000001, 'P000001', '经典原味苏打水', '箱', 0, 100, 100, 0, 8300, 100, 8400, '2026-07-15 17:30:00', '2026-07-15 17:30:00', '销售退货合格入库 1 箱')
ON DUPLICATE KEY UPDATE bill_id = VALUES(bill_id), bill_no = VALUES(bill_no),
source_bill_item_id = VALUES(source_bill_item_id), business_source_item_id = VALUES(business_source_item_id),
product_id = VALUES(product_id), product_code = VALUES(product_code), product_name = VALUES(product_name),
unit_name = VALUES(unit_name), quantity_precision = VALUES(quantity_precision), quantity = VALUES(quantity),
qualified_qty = VALUES(qualified_qty), defective_qty = VALUES(defective_qty), before_qty = VALUES(before_qty),
change_qty = VALUES(change_qty), after_qty = VALUES(after_qty), create_time = VALUES(create_time),
update_time = VALUES(update_time), remark = VALUES(remark);

-- 上述四笔已确认退货流水相对 003 脚本的 86 箱基线净减少 2 箱，最终为 84 箱。
UPDATE warehouse_stock
SET stock_qty = 8400,
    update_time = '2026-07-15 17:30:00',
    version = 4
WHERE id = 1940000000000000001
  AND warehouse_id = 1930000000000000001
  AND product_id = 1920000000000000001;

-- 种子业务一致性检查：以下结果均应为 0。
SELECT COUNT(*) AS invalid_purchase_return_seed_relation_count
FROM return_order ro
JOIN return_order_item roi ON roi.return_order_id = ro.id
LEFT JOIN purchase_order po ON po.id = ro.source_order_id
LEFT JOIN purchase_order_item poi ON poi.id = roi.source_order_item_id AND poi.purchase_order_id = po.id
LEFT JOIN warehouse w ON w.id = ro.warehouse_id
LEFT JOIN sys_user u ON u.id = ro.created_by_id
WHERE ro.id BETWEEN 2030000000000000001 AND 2030000000000000006
  AND (
    ro.return_type <> 'PURCHASE_RETURN' OR po.id IS NULL OR poi.id IS NULL
    OR ro.source_order_no <> po.purchase_no
    OR ro.party_id <> po.supplier_id OR ro.party_code <> po.supplier_code OR ro.party_name <> po.supplier_name
    OR ro.warehouse_id <> po.warehouse_id OR ro.warehouse_name <> po.warehouse_name
    OR w.id IS NULL OR w.warehouse_name <> ro.warehouse_name
    OR u.id IS NULL OR u.real_name <> ro.created_by_name
    OR roi.product_id <> poi.product_id OR roi.product_code <> poi.product_code OR roi.product_name <> poi.product_name
    OR roi.unit_name <> poi.unit_name OR roi.unit_price <> poi.unit_price OR roi.source_fulfilled_qty <> poi.inbound_qty
  );

SELECT COUNT(*) AS invalid_sales_return_seed_relation_count
FROM return_order ro
JOIN return_order_item roi ON roi.return_order_id = ro.id
LEFT JOIN sales_order so ON so.id = ro.source_order_id
LEFT JOIN sales_order_item soi ON soi.id = roi.source_order_item_id AND soi.sales_order_id = so.id
LEFT JOIN warehouse w ON w.id = ro.warehouse_id
LEFT JOIN sys_user u ON u.id = ro.created_by_id
WHERE ro.id BETWEEN 2040000000000000001 AND 2040000000000000006
  AND (
    ro.return_type <> 'SALES_RETURN' OR so.id IS NULL OR soi.id IS NULL
    OR ro.source_order_no <> so.sales_no
    OR ro.party_id <> so.customer_id OR ro.party_code <> so.customer_code OR ro.party_name <> so.customer_name
    OR ro.warehouse_id <> so.warehouse_id OR ro.warehouse_name <> so.warehouse_name
    OR w.id IS NULL OR w.warehouse_name <> ro.warehouse_name
    OR u.id IS NULL OR u.real_name <> ro.created_by_name
    OR roi.product_id <> soi.product_id OR roi.product_code <> soi.product_code OR roi.product_name <> soi.product_name
    OR roi.unit_name <> soi.unit_name OR roi.unit_price <> soi.unit_price OR roi.source_fulfilled_qty <> soi.outbound_qty
  );

SELECT COUNT(*) AS invalid_return_seed_amount_count
FROM (
    SELECT ro.id
    FROM return_order ro
    JOIN return_order_item roi ON roi.return_order_id = ro.id
    WHERE ro.id BETWEEN 2030000000000000001 AND 2040000000000000006
    GROUP BY ro.id, ro.total_amount, ro.status
    HAVING ro.total_amount <> ROUND(SUM(roi.total_amount), 2)
       OR SUM(roi.total_amount <> ROUND((CASE
            WHEN ro.status IN ('APPROVED', 'PARTIAL_EXECUTED') THEN roi.approved_qty
            WHEN ro.status = 'COMPLETED' THEN roi.processed_qty
            ELSE roi.requested_qty
          END) * roi.unit_price, 2)) > 0
) invalid_amount;

SELECT COUNT(*) AS invalid_return_seed_status_audit_count
FROM return_order ro
JOIN return_order_item roi ON roi.return_order_id = ro.id
WHERE ro.id BETWEEN 2030000000000000001 AND 2040000000000000006
  AND (
    (ro.status = 'DRAFT' AND (ro.submitted_at IS NOT NULL OR ro.approved_at IS NOT NULL OR roi.approved_qty <> 0 OR roi.processed_qty <> 0))
    OR (ro.status = 'SUBMITTED' AND (ro.submitted_at IS NULL OR ro.approved_at IS NOT NULL OR roi.approved_qty <> 0 OR roi.processed_qty <> 0))
    OR (ro.status IN ('APPROVED', 'PARTIAL_EXECUTED', 'COMPLETED') AND (ro.submitted_at IS NULL OR ro.approved_by_id IS NULL OR ro.approved_at IS NULL OR roi.approved_qty <= 0))
    OR (ro.status = 'APPROVED' AND roi.processed_qty <> 0)
    OR (ro.status = 'PARTIAL_EXECUTED' AND NOT (roi.processed_qty > 0 AND roi.processed_qty < roi.approved_qty))
    OR (ro.status = 'COMPLETED' AND roi.processed_qty <> roi.approved_qty)
    OR (ro.status = 'CANCELLED' AND (ro.status_reason = '' OR roi.processed_qty <> 0))
  );

SELECT COUNT(*) AS invalid_return_seed_occupancy_count
FROM (
    SELECT ro.return_type, roi.source_order_item_id
    FROM return_order ro
    JOIN return_order_item roi ON roi.return_order_id = ro.id
    WHERE ro.id BETWEEN 2030000000000000001 AND 2040000000000000006
    GROUP BY ro.return_type, roi.source_order_item_id
    HAVING SUM(CASE
        WHEN ro.status = 'SUBMITTED' THEN roi.requested_qty
        WHEN ro.status IN ('APPROVED', 'PARTIAL_EXECUTED') THEN roi.approved_qty
        WHEN ro.status = 'COMPLETED' THEN roi.processed_qty
        ELSE 0
      END) > MAX(roi.source_fulfilled_qty)
) invalid_occupancy;

SELECT COUNT(*) AS invalid_return_seed_work_bill_count
FROM return_order ro
JOIN return_order_item roi ON roi.return_order_id = ro.id
LEFT JOIN (
    SELECT 'PURCHASE_RETURN' AS return_type, ob.source_id AS return_order_id, obi.source_item_id AS return_order_item_id,
           ob.outbound_type AS bill_type, ob.source_type, ob.source_no, ob.warehouse_id, ob.status,
           obi.plan_qty, obi.processed_qty, obi.current_qty, obi.pending_qty,
           0 AS qualified_qty, 0 AS defective_qty, obi.stock_bill_item_id
    FROM outbound_bill ob
    JOIN outbound_bill_item obi ON obi.outbound_bill_id = ob.id
    WHERE ob.id BETWEEN 2050000000000000001 AND 2050000000000000003
    UNION ALL
    SELECT 'SALES_RETURN' AS return_type, ib.source_id AS return_order_id, ibi.source_item_id AS return_order_item_id,
           ib.inbound_type AS bill_type, ib.source_type, ib.source_no, ib.warehouse_id, ib.status,
           ibi.plan_qty, ibi.processed_qty, ibi.current_qty, ibi.pending_qty,
           ibi.qualified_qty, ibi.defective_qty, ibi.stock_bill_item_id
    FROM inbound_bill ib
    JOIN inbound_bill_item ibi ON ibi.inbound_bill_id = ib.id
    WHERE ib.id BETWEEN 2060000000000000001 AND 2060000000000000003
) wb ON wb.return_type = ro.return_type AND wb.return_order_id = ro.id AND wb.return_order_item_id = roi.id
WHERE ro.id BETWEEN 2030000000000000003 AND 2040000000000000005
  AND ro.status IN ('APPROVED', 'PARTIAL_EXECUTED', 'COMPLETED')
  AND (
    wb.return_order_id IS NULL OR wb.bill_type <> ro.return_type
    OR wb.source_type <> CONCAT(ro.return_type, '_ORDER') OR wb.source_no <> ro.return_no
    OR wb.warehouse_id <> ro.warehouse_id
    OR wb.status <> CASE WHEN ro.status = 'APPROVED' THEN 'PENDING_CONFIRM' ELSE 'CONFIRMED' END
    OR wb.plan_qty <> ROUND(roi.approved_qty * 100)
    OR wb.processed_qty <> 0
    OR wb.current_qty <> ROUND(CASE WHEN ro.status = 'APPROVED' THEN roi.approved_qty ELSE roi.processed_qty END * 100)
    OR wb.pending_qty <> wb.plan_qty - wb.processed_qty - wb.current_qty
    OR (ro.return_type = 'SALES_RETURN' AND wb.qualified_qty + wb.defective_qty <> wb.current_qty)
    OR (ro.status = 'APPROVED' AND wb.stock_bill_item_id IS NOT NULL)
    OR (ro.status IN ('PARTIAL_EXECUTED', 'COMPLETED') AND wb.stock_bill_item_id IS NULL)
  );

SELECT COUNT(*) AS invalid_return_seed_stock_ledger_count
FROM return_order ro
JOIN return_order_item roi ON roi.return_order_id = ro.id
LEFT JOIN (
    SELECT 'PURCHASE_RETURN' AS return_type, ob.source_id AS return_order_id, obi.source_item_id AS return_order_item_id,
           ob.id AS work_bill_id, ob.outbound_no AS work_bill_no, obi.id AS work_bill_item_id, obi.stock_bill_item_id
    FROM outbound_bill ob
    JOIN outbound_bill_item obi ON obi.outbound_bill_id = ob.id
    WHERE ob.id BETWEEN 2050000000000000002 AND 2050000000000000003
    UNION ALL
    SELECT 'SALES_RETURN' AS return_type, ib.source_id AS return_order_id, ibi.source_item_id AS return_order_item_id,
           ib.id AS work_bill_id, ib.inbound_no AS work_bill_no, ibi.id AS work_bill_item_id, ibi.stock_bill_item_id
    FROM inbound_bill ib
    JOIN inbound_bill_item ibi ON ibi.inbound_bill_id = ib.id
    WHERE ib.id BETWEEN 2060000000000000002 AND 2060000000000000003
) wb ON wb.return_type = ro.return_type AND wb.return_order_id = ro.id AND wb.return_order_item_id = roi.id
LEFT JOIN stock_bill_item sbi ON sbi.id = wb.stock_bill_item_id
LEFT JOIN stock_bill sb ON sb.id = sbi.bill_id
WHERE ro.id BETWEEN 2030000000000000004 AND 2040000000000000005
  AND ro.status IN ('PARTIAL_EXECUTED', 'COMPLETED')
  AND (
    sb.id IS NULL OR sbi.id IS NULL
    OR sb.bill_type <> ro.return_type OR sb.direction <> CASE WHEN ro.return_type = 'PURCHASE_RETURN' THEN 'OUTBOUND' ELSE 'INBOUND' END
    OR sb.source_bill_type <> CASE WHEN ro.return_type = 'PURCHASE_RETURN' THEN 'OUTBOUND_BILL' ELSE 'INBOUND_BILL' END
    OR sb.source_bill_id <> wb.work_bill_id OR sb.source_bill_no <> wb.work_bill_no
    OR sb.business_source_type <> CONCAT(ro.return_type, '_ORDER')
    OR sb.business_source_id <> ro.id OR sb.business_source_no <> ro.return_no
    OR sb.warehouse_id <> ro.warehouse_id OR sb.status <> 'CONFIRMED'
    OR sbi.source_bill_item_id <> wb.work_bill_item_id OR sbi.business_source_item_id <> roi.id
    OR sbi.product_id <> roi.product_id OR sbi.quantity <> ROUND(roi.processed_qty * 100)
    OR (ro.return_type = 'PURCHASE_RETURN' AND (sbi.change_qty <> -sbi.quantity OR sbi.qualified_qty <> 0 OR sbi.defective_qty <> 0))
    OR (ro.return_type = 'SALES_RETURN' AND (sbi.qualified_qty + sbi.defective_qty <> sbi.quantity OR sbi.change_qty <> sbi.qualified_qty))
    OR sbi.after_qty <> sbi.before_qty + sbi.change_qty
  );

WITH return_stock_events AS (
    SELECT sb.confirmed_at, sbi.id, sbi.before_qty, sbi.change_qty, sbi.after_qty,
           LAG(sbi.after_qty) OVER (ORDER BY sb.confirmed_at, sbi.id) AS previous_after_qty
    FROM stock_bill sb
    JOIN stock_bill_item sbi ON sbi.bill_id = sb.id
    WHERE sb.id BETWEEN 2070000000000000001 AND 2070000000000000004
), invalid_chain AS (
    SELECT COUNT(*) AS invalid_count
    FROM return_stock_events
    WHERE before_qty <> COALESCE(previous_after_qty, 8600)
       OR after_qty <> before_qty + change_qty
)
SELECT invalid_chain.invalid_count
       + CASE WHEN ws.stock_qty <> latest.after_qty THEN 1 ELSE 0 END
       AS invalid_return_seed_stock_balance_count
FROM invalid_chain
JOIN warehouse_stock ws ON ws.id = 1940000000000000001
JOIN (
    SELECT after_qty
    FROM return_stock_events
    ORDER BY confirmed_at DESC, id DESC
    LIMIT 1
) latest;

SELECT COUNT(*) AS invalid_purchase_return_seed_stock_count
FROM (
    SELECT ro.warehouse_id, roi.product_id
    FROM return_order ro
    JOIN return_order_item roi ON roi.return_order_id = ro.id
    LEFT JOIN warehouse_stock ws ON ws.warehouse_id = ro.warehouse_id AND ws.product_id = roi.product_id
    WHERE ro.id BETWEEN 2030000000000000001 AND 2030000000000000006
      AND ro.status IN ('APPROVED', 'PARTIAL_EXECUTED', 'COMPLETED')
    GROUP BY ro.warehouse_id, roi.product_id, ws.stock_qty, ws.locked_qty
    HAVING ws.stock_qty IS NULL
       OR SUM(roi.approved_qty - roi.processed_qty) > (ws.stock_qty - ws.locked_qty) / 100
) invalid_stock;
