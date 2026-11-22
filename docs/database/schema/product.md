# 商品和分类数据库结构

依据 2026-11-21 提供的 MySQL 结构导出整理，列类型、默认值和索引以建表文件为准；业务含义及关联仍需与代码逐项核实。

## `product`

表用途：产品表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 产品ID |
| `product_code` | `varchar(64)` | 否 | — | 产品编码，由后端生成且创建后不可修改 |
| `product_name` | `varchar(200)` | 否 | — | 产品名称 |
| `category_id` | `bigint` | 是 | NULL | 产品分类ID |
| `brand_name` | `varchar(100)` | 否 | '' | 品牌名称 |
| `unit_name` | `varchar(32)` | 否 | '件' | 单位名称 |
| `quantity_precision` | `tinyint` | 否 | '0' | 数量小数位：0-2，离散单位通常为0 |
| `specification` | `varchar(255)` | 否 | '' | 规格型号 |
| `barcode` | `varchar(64)` | 是 | NULL | 条码 |
| `reference_purchase_price` | `bigint` | 否 | `0` | 参考采购价，按分存储 |
| `reference_sale_price` | `bigint` | 否 | `0` | 参考销售价，按分存储 |
| `safety_stock_qty` | `bigint` | 否 | '0' | 安全库存数量，按100倍整数存储，例如12.50存1250 |
| `status` | `tinyint` | 否 | '1' | 状态：1启用，0禁用 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_product_code` (`product_code`)``
- ``UNIQUE KEY `uk_product_barcode` (`barcode`)``
- ``UNIQUE KEY `product_pk` (`barcode`)``
- ``KEY `idx_product_category` (`category_id`)``
- ``KEY `idx_product_name` (`product_name`)``
- ``KEY `idx_product_deleted_status` (`deleted`,`status`)``
- ``CONSTRAINT `chk_product_quantity_precision` CHECK ((`quantity_precision` between 0 and 2))``

## `product_category`

表用途：产品分类表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 分类ID |
| `parent_id` | `bigint` | 否 | '0' | 上级分类ID，顶级为0 |
| `category_name` | `varchar(100)` | 否 | — | 分类名称 |
| `status` | `tinyint` | 否 | '1' | 状态：1启用，0禁用 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``KEY `idx_product_category_parent` (`parent_id`)``
- ``KEY `idx_product_category_deleted_status` (`deleted`,`status`)``
