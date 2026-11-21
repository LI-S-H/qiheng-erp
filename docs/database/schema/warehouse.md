# 仓储和库存数据库结构

依据 2026-11-21 提供的 MySQL 结构导出整理，列类型、默认值和索引以建表文件为准；业务含义及关联仍需与代码逐项核实。

## `warehouse`

表用途：仓库表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 仓库ID |
| `warehouse_code` | `varchar(64)` | 否 | — | 仓库编码 |
| `warehouse_name` | `varchar(100)` | 否 | — | 仓库名称 |
| `contact_name` | `varchar(100)` | 否 | '' | 联系人 |
| `contact_phone` | `varchar(32)` | 否 | '' | 联系电话 |
| `address` | `varchar(255)` | 否 | '' | 仓库地址 |
| `status` | `tinyint` | 否 | '1' | 状态：1启用，0禁用 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_warehouse_code` (`warehouse_code`)``
- ``UNIQUE KEY `uk_warehouse_name` (`warehouse_name`)``
- ``KEY `idx_warehouse_deleted_status` (`deleted`,`status`)``

## `warehouse_stock`

表用途：库存余额表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 库存ID |
| `warehouse_id` | `bigint` | 否 | — | 仓库ID |
| `warehouse_code` | `varchar(64)` | 否 | — | 仓库编码冗余 |
| `warehouse_name` | `varchar(100)` | 否 | — | 仓库名称冗余 |
| `product_id` | `bigint` | 否 | — | 产品ID |
| `product_code` | `varchar(64)` | 否 | — | 产品编码冗余 |
| `product_name` | `varchar(200)` | 否 | — | 产品名称冗余 |
| `unit_name` | `varchar(32)` | 否 | '件' | 单位名称冗余 |
| `stock_qty` | `bigint` | 否 | '0' | 当前库存数量，按100倍整数存储 |
| `locked_qty` | `bigint` | 否 | '0' | 锁定库存数量，按100倍整数存储 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_warehouse_stock_product` (`warehouse_id`,`product_id`)``
- ``KEY `idx_warehouse_stock_product` (`product_id`)``
- ``KEY `idx_warehouse_stock_product_code` (`product_code`)``
- ``KEY `idx_warehouse_stock_product_name` (`product_name`)``

## `inbound_bill`

表用途：入库单主表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 入库单ID |
| `inbound_no` | `varchar(64)` | 否 | — | 入库单号 |
| `inbound_type` | `varchar(32)` | 否 | — | 类型：PURCHASE_IN、SALES_RETURN、ADJUST_IN |
| `source_type` | `varchar(32)` | 否 | '' | 来源类型：PURCHASE_ORDER、SALES_RETURN_ORDER、STOCK_ADJUST |
| `source_id` | `bigint` | 是 | NULL | 来源单据ID |
| `source_no` | `varchar(64)` | 否 | '' | 来源单据号 |
| `source_party_id` | `bigint` | 是 | NULL | 来源对象ID，采购入库为供应商、销售退货为客户、调整入库为受影响仓库 |
| `source_party_name` | `varchar(200)` | 否 | '' | 来源供应商、客户或调整仓库名称快照 |
| `entry_mode` | `varchar(32)` | 否 | 'SOURCE_GENERATED' | 录入方式：SOURCE_GENERATED、MANUAL_SUPPLEMENT、MANUAL_ADJUSTMENT |
| `warehouse_id` | `bigint` | 否 | — | 仓库ID |
| `warehouse_name` | `varchar(100)` | 否 | — | 仓库名称快照 |
| `status` | `varchar(32)` | 否 | 'PENDING_CONFIRM' | 状态：DRAFT、PENDING_CONFIRM、CONFIRMED、CANCELLED |
| `expected_arrival_date` | `date` | 是 | NULL | 采购订单预计到货日期快照；仅系统生成的采购入库单有值 |
| `confirmed_by_id` | `bigint` | 是 | NULL | 确认人ID |
| `confirmed_by_name` | `varchar(100)` | 否 | '' | 确认人姓名 |
| `confirmed_at` | `datetime` | 是 | NULL | 确认时间 |
| `created_by_id` | `bigint` | 是 | NULL | 创建人ID |
| `created_by_name` | `varchar(100)` | 否 | '' | 创建人姓名 |
| `responsible_by_id` | `bigint` | 是 | NULL | 业务负责人ID，确认时填入审核人 |
| `responsible_by_name` | `varchar(100)` | 是 | NULL | 业务负责人姓名快照，确认时填入审核人 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `manual_reason` | `varchar(500)` | 否 | '' | 手工补录或库存调整原因 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除:0正常,1删除 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_inbound_bill_no` (`inbound_no`)``
- ``KEY `idx_inbound_bill_source` (`source_type`,`source_id`)``
- ``KEY `idx_inbound_bill_source_no` (`source_no`)``
- ``KEY `idx_inbound_bill_party` (`source_party_id`)``
- ``KEY `idx_inbound_bill_warehouse` (`warehouse_id`)``
- ``KEY `idx_inbound_bill_type_status` (`inbound_type`,`status`)``
- ``KEY `idx_outbound_bill_deleted_status` (`deleted`,`status`)``
- ``KEY `idx_inbound_bill_due_scan` (`inbound_type`,`status`,`deleted`,`expected_arrival_date`)``
- ``KEY `idx_inbound_bill_supplier_confirmed` (`source_party_id`,`inbound_type`,`status`,`confirmed_at`,`deleted`)``

## `inbound_bill_item`

表用途：入库单明细表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 入库单明细ID |
| `inbound_bill_id` | `bigint` | 否 | — | 入库单ID |
| `inbound_no` | `varchar(64)` | 否 | — | 入库单号冗余 |
| `source_item_id` | `bigint` | 是 | NULL | 来源单据明细ID |
| `product_id` | `bigint` | 否 | — | 产品ID |
| `product_code` | `varchar(64)` | 否 | — | 产品编码快照 |
| `product_name` | `varchar(200)` | 否 | — | 产品名称快照 |
| `unit_name` | `varchar(32)` | 否 | '件' | 单位名称快照 |
| `quantity_precision` | `tinyint` | 否 | '0' | 数量小数位快照：0-2 |
| `plan_qty` | `bigint` | 否 | '0' | 来源计划数量，按100倍整数存储 |
| `processed_qty` | `bigint` | 否 | '0' | 生成本单前累计已入库数量，按100倍整数存储 |
| `current_qty` | `bigint` | 否 | '0' | 本次入库数量，按100倍整数存储 |
| `unit_price` | `bigint` | 否 | '0' | 入库单价快照(分)。采购入库/销售退货入库在创建明细时由系统从来源订单行复制;手补录/调整入库保持 0(无来源单价) |
| `pending_qty` | `bigint` | 否 | '0' | 确认本单后剩余未入库数量，按100倍整数存储 |
| `qualified_qty` | `bigint` | 否 | '0' | 合格数量，按100倍整数存储；采购入库和销售退货入库使用 |
| `defective_qty` | `bigint` | 否 | '0' | 不合格数量，按100倍整数存储；采购入库和销售退货入库使用 |
| `stock_bill_item_id` | `bigint` | 是 | NULL | 确认后生成的库存流水明细ID |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``KEY `idx_inbound_bill_item_bill` (`inbound_bill_id`)``
- ``KEY `idx_inbound_bill_item_product` (`product_id`)``
- ``KEY `idx_inbound_bill_item_source_item` (`source_item_id`)``
- ``KEY `idx_inbound_bill_item_stock_item` (`stock_bill_item_id`)``
- ``KEY `idx_inbound_bill_item_bill_unit_price` (`inbound_bill_id`,`unit_price`)``
- ``CONSTRAINT `chk_inbound_bill_item_quantity_precision` CHECK ((`quantity_precision` between 0 and 2))``

## `outbound_bill`

表用途：出库单主表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 出库单ID |
| `outbound_no` | `varchar(64)` | 否 | — | 出库单号 |
| `outbound_type` | `varchar(32)` | 否 | — | 类型：SALES_OUT、PURCHASE_RETURN、ADJUST_OUT |
| `source_type` | `varchar(32)` | 否 | '' | 来源类型：SALES_ORDER、PURCHASE_RETURN_ORDER、STOCK_ADJUST |
| `source_id` | `bigint` | 是 | NULL | 来源单据ID |
| `source_no` | `varchar(64)` | 否 | '' | 来源单据号 |
| `source_party_id` | `bigint` | 是 | NULL | 来源对象ID，销售出库为客户、采购退货为供应商、调整出库为受影响仓库 |
| `source_party_name` | `varchar(200)` | 否 | '' | 来源客户、供应商或调整仓库名称快照 |
| `entry_mode` | `varchar(32)` | 否 | 'SOURCE_GENERATED' | 录入方式：SOURCE_GENERATED、MANUAL_SUPPLEMENT、MANUAL_ADJUSTMENT |
| `warehouse_id` | `bigint` | 否 | — | 仓库ID |
| `warehouse_name` | `varchar(100)` | 否 | — | 仓库名称快照 |
| `status` | `varchar(32)` | 否 | 'PENDING_CONFIRM' | 状态：DRAFT、PENDING_CONFIRM、CONFIRMED、CANCELLED |
| `confirmed_by_id` | `bigint` | 是 | NULL | 确认人ID |
| `confirmed_by_name` | `varchar(100)` | 否 | '' | 确认人姓名 |
| `confirmed_at` | `datetime` | 是 | NULL | 确认时间 |
| `created_by_id` | `bigint` | 是 | NULL | 创建人ID |
| `created_by_name` | `varchar(100)` | 否 | '' | 创建人姓名 |
| `responsible_by_id` | `bigint` | 是 | NULL | 业务负责人ID，确认时填入审核人 |
| `responsible_by_name` | `varchar(100)` | 是 | NULL | 业务负责人姓名快照，确认时填入审核人 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `manual_reason` | `varchar(500)` | 否 | '' | 手工补录或库存调整原因 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除:0正常,1删除 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_outbound_bill_no` (`outbound_no`)``
- ``KEY `idx_outbound_bill_source` (`source_type`,`source_id`)``
- ``KEY `idx_outbound_bill_source_no` (`source_no`)``
- ``KEY `idx_outbound_bill_party` (`source_party_id`)``
- ``KEY `idx_outbound_bill_warehouse` (`warehouse_id`)``
- ``KEY `idx_outbound_bill_type_status` (`outbound_type`,`status`)``
- ``KEY `idx_outbound_bill_deleted_status` (`deleted`,`status`)``

## `outbound_bill_item`

表用途：出库单明细表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 出库单明细ID |
| `outbound_bill_id` | `bigint` | 否 | — | 出库单ID |
| `outbound_no` | `varchar(64)` | 否 | — | 出库单号冗余 |
| `source_item_id` | `bigint` | 是 | NULL | 来源单据明细ID |
| `product_id` | `bigint` | 否 | — | 产品ID |
| `product_code` | `varchar(64)` | 否 | — | 产品编码快照 |
| `product_name` | `varchar(200)` | 否 | — | 产品名称快照 |
| `unit_name` | `varchar(32)` | 否 | '件' | 单位名称快照 |
| `quantity_precision` | `tinyint` | 否 | '0' | 数量小数位快照：0-2 |
| `plan_qty` | `bigint` | 否 | '0' | 来源计划数量，按100倍整数存储 |
| `processed_qty` | `bigint` | 否 | '0' | 生成本单前累计已出库数量，按100倍整数存储 |
| `current_qty` | `bigint` | 否 | '0' | 本次出库数量，按100倍整数存储 |
| `pending_qty` | `bigint` | 否 | '0' | 确认本单后剩余未出库数量，按100倍整数存储 |
| `qualified_qty` | `bigint` | 否 | '0' | 合格数量，按100倍整数存储；采购退货出库使用 |
| `defective_qty` | `bigint` | 否 | '0' | 不合格数量，按100倍整数存储；采购退货出库使用 |
| `stock_bill_item_id` | `bigint` | 是 | NULL | 确认后生成的库存流水明细ID |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``KEY `idx_outbound_bill_item_bill` (`outbound_bill_id`)``
- ``KEY `idx_outbound_bill_item_product` (`product_id`)``
- ``KEY `idx_outbound_bill_item_source_item` (`source_item_id`)``
- ``KEY `idx_outbound_bill_item_stock_item` (`stock_bill_item_id`)``
- ``CONSTRAINT `chk_outbound_bill_item_quantity_precision` CHECK ((`quantity_precision` between 0 and 2))``

## `stock_bill`

表用途：库存流水凭证主表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 库存流水凭证ID |
| `bill_no` | `varchar(64)` | 否 | — | 库存流水号 |
| `bill_type` | `varchar(32)` | 否 | — | 类型：PURCHASE_IN、SALES_OUT、PURCHASE_RETURN、SALES_RETURN、ADJUST_IN、ADJUST_OUT |
| `work_bill_id` | `bigint` | 否 | — | 已确认入库单或出库单 ID |
| `business_source_id` | `bigint` | 是 | NULL | 原业务单据ID |
| `business_source_no` | `varchar(64)` | 否 | '' | 原业务单据号 |
| `entry_mode` | `varchar(32)` | 否 | 'SOURCE_GENERATED' | 录入方式：SOURCE_GENERATED、MANUAL_SUPPLEMENT、MANUAL_ADJUSTMENT |
| `warehouse_id` | `bigint` | 否 | — | 仓库ID |
| `warehouse_name` | `varchar(100)` | 否 | — | 仓库名称快照 |
| `confirmed_by_id` | `bigint` | 否 | — | 确认人ID |
| `confirmed_by_name` | `varchar(100)` | 否 | — | 确认人姓名 |
| `confirmed_at` | `datetime` | 否 | — | 确认时间 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_stock_bill_no` (`bill_no`)``
- ``KEY `idx_stock_bill_business_source_no` (`business_source_no`)``
- ``KEY `idx_stock_bill_warehouse` (`warehouse_id`)``
- ``KEY `idx_stock_bill_entry_mode_confirmed_at` (`entry_mode`,`confirmed_at`)``
- ``KEY `idx_stock_bill_work_bill` (`work_bill_id`)``
- ``KEY `idx_stock_bill_bill_type_confirmed_at` (`bill_type`,`confirmed_at`)``
- ``KEY `idx_stock_bill_business_source` (`bill_type`,`business_source_id`)``

## `stock_bill_item`

表用途：库存流水凭证明细表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 库存流水凭证明细ID |
| `bill_id` | `bigint` | 否 | — | 库存流水凭证ID |
| `work_bill_item_id` | `bigint` | 否 | — | 已确认入库单或出库单明细 ID |
| `business_source_item_id` | `bigint` | 是 | NULL | 原业务来源明细ID |
| `product_id` | `bigint` | 否 | — | 产品ID |
| `product_code` | `varchar(64)` | 否 | — | 产品编码快照 |
| `product_name` | `varchar(200)` | 否 | — | 产品名称快照 |
| `unit_name` | `varchar(32)` | 否 | '件' | 单位名称快照 |
| `quantity_precision` | `tinyint` | 否 | '0' | 数量小数位快照：0-2 |
| `qualified_qty` | `bigint` | 否 | '0' | 合格数量，按100倍整数存储；仅质检适用类型使用 |
| `defective_qty` | `bigint` | 否 | '0' | 不合格数量，按100倍整数存储；仅质检适用类型使用 |
| `before_qty` | `bigint` | 否 | '0' | 变动前库存，按100倍整数存储 |
| `change_qty` | `bigint` | 否 | '0' | 库存变动数量，入库为正、出库为负，按100倍整数存储 |
| `after_qty` | `bigint` | 否 | '0' | 变动后库存，按100倍整数存储 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``KEY `idx_stock_bill_item_bill` (`bill_id`)``
- ``KEY `idx_stock_bill_item_product` (`product_id`)``
- ``KEY `idx_stock_bill_item_business_source_item` (`business_source_item_id`)``
- ``KEY `idx_stock_bill_item_work_item` (`work_bill_item_id`)``
- ``KEY `idx_stock_bill_item_source_bill` (`business_source_item_id`,`bill_id`)``
- ``CONSTRAINT `chk_stock_bill_item_quantity_precision` CHECK ((`quantity_precision` between 0 and 2))``
