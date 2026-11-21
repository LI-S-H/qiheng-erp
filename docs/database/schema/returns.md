# 采购及销售退货数据库结构

依据 2026-11-21 提供的 MySQL 结构导出整理，列类型、默认值和索引以建表文件为准；业务含义及关联仍需与代码逐项核实。

## `return_order`

表用途：统一退货单主表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 退货单ID |
| `return_no` | `varchar(64)` | 否 | — | 退货单号 |
| `return_type` | `varchar(32)` | 否 | — | 退货类型：SALES_RETURN、PURCHASE_RETURN，创建后不可修改 |
| `source_order_id` | `bigint` | 否 | — | 原销售订单ID或采购订单ID |
| `source_order_no` | `varchar(64)` | 否 | — | 原订单号快照 |
| `party_id` | `bigint` | 否 | — | 销售退货为客户ID，采购退货为供应商ID |
| `party_code` | `varchar(64)` | 否 | — | 客户或供应商编码快照 |
| `party_name` | `varchar(200)` | 否 | — | 客户或供应商名称快照 |
| `warehouse_id` | `bigint` | 否 | — | 退货执行仓库ID |
| `warehouse_name` | `varchar(100)` | 否 | — | 仓库名称快照 |
| `expected_execution_date` | `date` | 是 | NULL | 预计退货执行日期；草稿可空，提交前必填 |
| `handling_type` | `varchar(32)` | 否 | 'REFUND' | 处理方式：REFUND、EXCHANGE、OTHER |
| `reason_code` | `varchar(32)` | 否 | 'OTHER' | 退货原因编码 |
| `return_reason` | `varchar(500)` | 否 | '' | 退货原因补充说明 |
| `total_amount` | `bigint` | 否 | '0' | 当前有效退货总金额，放大100倍保存，17600表示176.00 |
| `status` | `varchar(32)` | 否 | 'DRAFT' | 状态：DRAFT、SUBMITTED、APPROVED、PARTIAL_EXECUTED、COMPLETED、CANCELLED |
| `status_reason` | `varchar(500)` | 否 | '' | 最近一次审核退回或取消原因 |
| `created_by_id` | `bigint` | 是 | NULL | 创建人ID |
| `created_by_name` | `varchar(100)` | 否 | '' | 创建人姓名快照 |
| `submitted_at` | `datetime` | 是 | NULL | 提交时间 |
| `approved_by_id` | `bigint` | 是 | NULL | 审核人ID |
| `approved_by_name` | `varchar(100)` | 否 | '' | 审核人姓名快照 |
| `approved_at` | `datetime` | 是 | NULL | 审核时间 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_return_order_no` (`return_no`)``
- ``KEY `idx_return_order_source` (`return_type`,`source_order_id`)``
- ``KEY `idx_return_order_party` (`return_type`,`party_id`)``
- ``KEY `idx_return_order_warehouse` (`warehouse_id`)``
- ``KEY `idx_return_order_status` (`return_type`,`deleted`,`status`,`create_time`)``

## `return_order_item`

表用途：统一退货单明细表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 退货明细ID |
| `return_order_id` | `bigint` | 否 | — | 退货单主表ID |
| `source_order_item_id` | `bigint` | 否 | — | 原销售订单明细ID或采购订单明细ID |
| `product_id` | `bigint` | 否 | — | 产品ID |
| `product_code` | `varchar(64)` | 否 | — | 产品编码快照 |
| `product_name` | `varchar(200)` | 否 | — | 产品名称快照 |
| `unit_name` | `varchar(32)` | 否 | '件' | 单位名称快照 |
| `quantity_precision` | `tinyint` | 否 | '0' | 数量小数位快照：0-2 |
| `source_fulfilled_qty` | `bigint` | 否 | '0' | 创建退货明细时原订单累计已出库或已入库数量快照，放大100倍保存 |
| `requested_qty` | `bigint` | 否 | '0' | 申请退货数量，放大100倍保存，500表示5.00 |
| `approved_qty` | `bigint` | 否 | '0' | 审核通过数量，放大100倍保存 |
| `processed_qty` | `bigint` | 否 | '0' | 仓库累计确认的实际处理总量，放大100倍保存 |
| `unit_price` | `bigint` | 否 | '0' | 原采购或销售订单明细单价快照，放大100倍保存，3520表示35.20 |
| `total_amount` | `bigint` | 否 | '0' | 当前有效明细金额，放大100倍保存，17600表示176.00 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `remark` | `varchar(500)` | 否 | '' | 明细备注 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_return_order_item_source` (`return_order_id`,`source_order_item_id`)``
- ``KEY `idx_return_order_item_order` (`return_order_id`)``
- ``KEY `idx_return_order_item_product` (`product_id`)``
- ``KEY `idx_return_order_item_source_item` (`source_order_item_id`)``
- ``CONSTRAINT `chk_return_order_item_approved_qty` CHECK (((`approved_qty` >= 0) and (`approved_qty` <= `requested_qty`)))``
- ``CONSTRAINT `chk_return_order_item_processed_qty` CHECK (((`processed_qty` >= 0) and (`processed_qty` <= `approved_qty`)))``
- ``CONSTRAINT `chk_return_order_item_quantity_precision` CHECK ((`quantity_precision` between 0 and 2))``
- ``CONSTRAINT `chk_return_order_item_requested_qty` CHECK (((`requested_qty` > 0) and (`requested_qty` <= `source_fulfilled_qty`)))``
- ``CONSTRAINT `chk_return_order_item_source_fulfilled_qty` CHECK ((`source_fulfilled_qty` > 0))``
