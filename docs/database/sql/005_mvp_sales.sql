-- MVP 客户销售库表设计：极简版
-- 数据库：MySQL 8
-- 说明：主键由 MyBatis-Plus ASSIGN_ID 生成，因此不使用 AUTO_INCREMENT。

CREATE TABLE IF NOT EXISTS customer (
    id BIGINT NOT NULL COMMENT '客户ID',
    customer_code VARCHAR(64) NOT NULL COMMENT '客户编码',
    customer_name VARCHAR(200) NOT NULL COMMENT '客户名称',
    contact_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '联系人',
    contact_phone VARCHAR(32) NOT NULL DEFAULT '' COMMENT '联系电话',
    address VARCHAR(255) NOT NULL DEFAULT '' COMMENT '地址',
    credit_limit DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '信用额度',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
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
    total_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '订单总金额',
    expected_delivery_date DATE DEFAULT NULL COMMENT '预计发货日期',
    locked_at DATETIME DEFAULT NULL COMMENT '库存锁定时间',
    created_by_id BIGINT DEFAULT NULL COMMENT '创建人ID',
    created_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '创建人姓名',
    submitted_at DATETIME DEFAULT NULL COMMENT '提交时间',
    approved_by_id BIGINT DEFAULT NULL COMMENT '审核人ID',
    approved_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '审核人姓名',
    approved_at DATETIME DEFAULT NULL COMMENT '审核时间',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_sales_order_no (sales_no),
    KEY idx_sales_order_customer (customer_id),
    KEY idx_sales_order_warehouse (warehouse_id),
    KEY idx_sales_order_status (status),
    KEY idx_sales_order_created_at (created_at),
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
    quantity DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '销售数量',
    locked_qty DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '已锁定库存数量',
    outbound_qty DECIMAL(18,4) NOT NULL DEFAULT 0.0000 COMMENT '已出库数量',
    unit_price DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '销售单价',
    total_amount DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '明细金额',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_sales_order_item_order (sales_order_id),
    KEY idx_sales_order_item_product (product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='销售订单明细表';
