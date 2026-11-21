# 销售与客户数据库结构

依据 2026-11-21 提供的 MySQL 结构导出整理，列类型、默认值和索引以建表文件为准；业务含义及关联仍需与代码逐项核实。

## `customer`

表用途：客户表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 客户ID |
| `customer_code` | `varchar(64)` | 否 | — | 客户编码 |
| `customer_name` | `varchar(200)` | 否 | — | 客户名称 |
| `contact_name` | `varchar(100)` | 否 | '' | 联系人 |
| `contact_phone` | `varchar(32)` | 否 | '' | 联系电话 |
| `address` | `varchar(255)` | 否 | '' | 地址 |
| `credit_limit` | `bigint` | 否 | '0' | 信用额度，放大100倍保存，18000000表示180000.00 |
| `status` | `tinyint` | 否 | '1' | 状态：1启用，0禁用 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `updated_by_id` | `bigint` | 否 | — | 最后维护人ID，新建/编辑/启停时由后端写入 |
| `updated_by_name` | `varchar(100)` | 否 | '' | 最后维护人姓名 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_customer_code` (`customer_code`)``
- ``KEY `idx_customer_name` (`customer_name`)``
- ``KEY `idx_customer_deleted_status` (`deleted`,`status`)``

## `sales_order`

表用途：销售订单主表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 销售订单ID |
| `sales_no` | `varchar(64)` | 否 | — | 销售单号 |
| `customer_id` | `bigint` | 否 | — | 客户ID |
| `customer_code` | `varchar(64)` | 否 | — | 客户编码冗余 |
| `customer_name` | `varchar(200)` | 否 | — | 客户名称冗余 |
| `warehouse_id` | `bigint` | 否 | — | 出库仓库ID |
| `warehouse_name` | `varchar(100)` | 否 | — | 出库仓库名称冗余 |
| `status` | `varchar(32)` | 否 | 'DRAFT' | 状态：DRAFT、SUBMITTED、APPROVED、PARTIAL_OUTBOUND、OUTBOUND_DONE、CANCELLED |
| `total_amount` | `bigint` | 否 | '0' | 订单总金额，放大100倍保存，0表示0.00 |
| `expected_delivery_date` | `date` | 是 | NULL | 预计发货日期 |
| `locked_at` | `datetime` | 是 | NULL | 库存锁定时间 |
| `created_by_id` | `bigint` | 是 | NULL | 创建人ID |
| `created_by_name` | `varchar(100)` | 否 | '' | 创建人姓名 |
| `submitted_at` | `datetime` | 是 | NULL | 提交时间 |
| `submitted_by_id` | `bigint` | 是 | NULL | 提交人ID |
| `submitted_by_name` | `varchar(100)` | 否 | '' | 提交人姓名 |
| `approved_by_id` | `bigint` | 是 | NULL | 审核人ID |
| `approved_by_name` | `varchar(100)` | 否 | '' | 审核人姓名 |
| `approved_at` | `datetime` | 是 | NULL | 审核时间 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_sales_order_no` (`sales_no`)``
- ``KEY `idx_sales_order_customer` (`customer_id`)``
- ``KEY `idx_sales_order_warehouse` (`warehouse_id`)``
- ``KEY `idx_sales_order_status` (`status`)``
- ``KEY `idx_sales_order_create_time` (`create_time`)``
- ``KEY `idx_sales_order_deleted_status` (`deleted`,`status`)``

## `sales_order_item`

表用途：销售订单明细表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 明细ID |
| `sales_order_id` | `bigint` | 否 | — | 销售订单ID |
| `sales_no` | `varchar(64)` | 否 | — | 销售单号冗余 |
| `product_id` | `bigint` | 否 | — | 产品ID |
| `product_code` | `varchar(64)` | 否 | — | 产品编码冗余 |
| `product_name` | `varchar(200)` | 否 | — | 产品名称冗余 |
| `unit_name` | `varchar(32)` | 否 | '件' | 单位名称冗余 |
| `quantity_precision` | `tinyint` | 否 | '0' | 数量小数位快照：0-2，下单时从 product.quantity_precision 固化 |
| `quantity` | `bigint` | 否 | '0' | 销售数量，放大100倍保存，N 表示 N/100.00 |
| `locked_qty` | `bigint` | 否 | '0' | 已锁定库存数量，放大100倍保存 |
| `outbound_qty` | `bigint` | 否 | '0' | 已出库数量，放大100倍保存 |
| `unit_price` | `bigint` | 否 | '0' | 销售单价，放大100倍保存，0表示0.00 |
| `total_amount` | `bigint` | 否 | '0' | 明细金额，放大100倍保存，0表示0.00 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``KEY `idx_sales_order_item_order` (`sales_order_id`)``
- ``KEY `idx_sales_order_item_product` (`product_id`)``
- ``CONSTRAINT `chk_sales_order_item_quantity_precision` CHECK ((`quantity_precision` between 0 and 2))``
