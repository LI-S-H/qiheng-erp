-- MVP 产品资料库表设计：极简版
-- 数据库：MySQL 8
-- 说明：主键由 MyBatis-Plus ASSIGN_ID 生成，因此不使用 AUTO_INCREMENT。

USE erp;

CREATE TABLE IF NOT EXISTS product_category (
    id BIGINT NOT NULL COMMENT '分类ID',
    parent_id BIGINT NOT NULL DEFAULT 0 COMMENT '上级分类ID，顶级为0',
    category_name VARCHAR(100) NOT NULL COMMENT '分类名称',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    KEY idx_product_category_parent (parent_id),
    KEY idx_product_category_deleted_status (deleted, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='产品分类表';

CREATE TABLE IF NOT EXISTS product (
    id BIGINT NOT NULL COMMENT '产品ID',
    product_code VARCHAR(64) NOT NULL COMMENT '产品编码，由后端生成且创建后不可修改',
    product_name VARCHAR(200) NOT NULL COMMENT '产品名称',
    category_id BIGINT DEFAULT NULL COMMENT '产品分类ID',
    brand_name VARCHAR(100) NOT NULL DEFAULT '' COMMENT '品牌名称',
    unit_name VARCHAR(32) NOT NULL DEFAULT '件' COMMENT '单位名称',
    quantity_precision TINYINT NOT NULL DEFAULT 0 COMMENT '数量小数位：0-2，离散单位通常为0',
    specification VARCHAR(255) NOT NULL DEFAULT '' COMMENT '规格型号',
    barcode VARCHAR(64) DEFAULT NULL COMMENT '条码',
    reference_purchase_price DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '参考采购价',
    reference_sale_price DECIMAL(18,2) NOT NULL DEFAULT 0.00 COMMENT '参考销售价',
    safety_stock_qty BIGINT NOT NULL DEFAULT 0 COMMENT '安全库存数量，按100倍整数存储，例如12.50存1250',
    status TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用，0禁用',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
    remark VARCHAR(500) NOT NULL DEFAULT '' COMMENT '备注',
    version INT NOT NULL DEFAULT 0 COMMENT '乐观锁版本号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_product_code (product_code),
    KEY idx_product_category (category_id),
    KEY idx_product_name (product_name),
    KEY idx_product_barcode (barcode),
    KEY idx_product_deleted_status (deleted, status),
    CONSTRAINT chk_product_quantity_precision CHECK (quantity_precision BETWEEN 0 AND 2)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='产品表';
