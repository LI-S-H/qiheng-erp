-- MVP 仓库库存库表设计：出入库流水版
-- 数据库：MySQL 8
-- 说明：主键由 MyBatis-Plus ASSIGN_ID 生成，因此不使用 AUTO_INCREMENT。

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
    stock_qty BIGINT NOT NULL DEFAULT 0 COMMENT '当前库存数量，按100倍整数存储，例如12.50存1250',
    locked_qty BIGINT NOT NULL DEFAULT 0 COMMENT '锁定库存数量，按100倍整数存储，例如12.50存1250',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_warehouse_stock_product (warehouse_id, product_id),
    KEY idx_warehouse_stock_product (product_id),
    KEY idx_warehouse_stock_product_code (product_code),
    KEY idx_warehouse_stock_product_name (product_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='库存余额表';

CREATE TABLE IF NOT EXISTS stock_bill (
    id BIGINT NOT NULL COMMENT '出入库流水ID',
    bill_no VARCHAR(64) NOT NULL COMMENT '出入库流水号',
    bill_type VARCHAR(32) NOT NULL COMMENT '类型：PURCHASE_IN、SALES_OUT、PURCHASE_RETURN、SALES_RETURN、ADJUST_IN、ADJUST_OUT',
    source_type VARCHAR(32) NOT NULL DEFAULT '' COMMENT '来源类型：PURCHASE_ORDER、SALES_ORDER、PURCHASE_RETURN_ORDER、SALES_RETURN_ORDER、STOCK_ADJUST',
    source_id BIGINT DEFAULT NULL COMMENT '来源单据ID',
    source_no VARCHAR(64) NOT NULL DEFAULT '' COMMENT '来源单据号',
    entry_mode VARCHAR(32) NOT NULL DEFAULT 'SOURCE_GENERATED' COMMENT '录入方式：SOURCE_GENERATED、MANUAL_SUPPLEMENT、MANUAL_ADJUSTMENT',
    warehouse_id BIGINT NOT NULL COMMENT '仓库ID',
    warehouse_name VARCHAR(100) NOT NULL COMMENT '仓库名称冗余',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '状态：DRAFT、CONFIRMED、CANCELLED',
    confirmed_by_id BIGINT DEFAULT NULL COMMENT '确认人ID',
    confirmed_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '确认人姓名',
    confirmed_at DATETIME DEFAULT NULL COMMENT '确认时间',
    created_by_id BIGINT DEFAULT NULL COMMENT '创建人ID',
    created_by_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '创建人姓名',
    responsible_by_id BIGINT NOT NULL COMMENT '业务负责人ID，手工单据取当前登录用户',
    responsible_by_name VARCHAR(100) NOT NULL COMMENT '业务负责人姓名快照',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    manual_reason VARCHAR(500) NOT NULL DEFAULT '' COMMENT '手工补录或库存调整原因，来源生成凭证为空',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    PRIMARY KEY (id),
    UNIQUE KEY uk_stock_bill_no (bill_no),
    KEY idx_stock_bill_source (source_type, source_id),
    KEY idx_stock_bill_source_no (source_no),
    KEY idx_stock_bill_warehouse (warehouse_id),
    KEY idx_stock_bill_type_status (bill_type, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出入库流水主表';

CREATE TABLE IF NOT EXISTS stock_bill_item (
    id BIGINT NOT NULL COMMENT '明细ID',
    bill_id BIGINT NOT NULL COMMENT '出入库流水ID',
    bill_no VARCHAR(64) NOT NULL COMMENT '出入库流水号冗余',
    source_item_id BIGINT DEFAULT NULL COMMENT '来源单据明细ID',
    product_id BIGINT NOT NULL COMMENT '产品ID',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码冗余',
    product_name VARCHAR(200) NOT NULL COMMENT '产品名称冗余',
    unit_name VARCHAR(32) NOT NULL DEFAULT '件' COMMENT '单位名称冗余',
    quantity_precision TINYINT NOT NULL DEFAULT 0 COMMENT '数量小数位快照：0-2',
    quantity BIGINT NOT NULL DEFAULT 0 COMMENT '本次出入库数量，正数，按100倍整数存储',
    qualified_qty BIGINT NOT NULL DEFAULT 0 COMMENT '合格数量，按100倍整数存储',
    defective_qty BIGINT NOT NULL DEFAULT 0 COMMENT '不合格数量，按100倍整数存储',
    before_qty BIGINT NOT NULL DEFAULT 0 COMMENT '变动前库存，按100倍整数存储',
    change_qty BIGINT NOT NULL DEFAULT 0 COMMENT '库存变动数量，入库为正、出库为负，按100倍整数存储',
    after_qty BIGINT NOT NULL DEFAULT 0 COMMENT '变动后库存，按100倍整数存储',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    PRIMARY KEY (id),
    KEY idx_stock_bill_item_bill (bill_id),
    KEY idx_stock_bill_item_product (product_id),
    KEY idx_stock_bill_item_source_item (source_item_id),
    CONSTRAINT chk_stock_bill_item_quantity_precision CHECK (quantity_precision BETWEEN 0 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='出入库流水明细表';
