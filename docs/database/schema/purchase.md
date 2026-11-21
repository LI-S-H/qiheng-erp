# 采购与供应商数据库结构

依据 2026-11-21 提供的 MySQL 结构导出整理，列类型、默认值和索引以建表文件为准；业务含义及关联仍需与代码逐项核实。

## `supplier`

表用途：供应商表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 供应商ID |
| `supplier_code` | `varchar(64)` | 否 | — | 供应商编码 |
| `supplier_name` | `varchar(200)` | 否 | — | 供应商名称 |
| `contact_name` | `varchar(100)` | 否 | '' | 联系人 |
| `contact_phone` | `varchar(32)` | 否 | '' | 联系电话 |
| `address` | `varchar(255)` | 否 | '' | 地址 |
| `payment_terms` | `varchar(100)` | 否 | '' | 付款条件 |
| `overall_score` | `int` | 是 | NULL | 供应商综合评分，INT×100，NULL表示样本不足 |
| `delivery_score` | `int` | 是 | NULL | 供应商交付评分，INT×100，NULL表示样本不足 |
| `quality_score` | `int` | 是 | NULL | 供应商质量评分，INT×100，NULL表示样本不足 |
| `price_score` | `int` | 是 | NULL | 供应商价格评分，INT×100，NULL表示样本不足 |
| `service_score` | `int` | 是 | NULL | 人工服务评分，INT×100，NULL表示尚未人工设定 |
| `service_score_reason` | `varchar(500)` | 否 | '' | 当前人工服务评分原因；服务分为空时为空字符串 |
| `avg_delivery_days` | `decimal(10,2)` | 是 | NULL | 最近180天完全入库订单的金额加权平均到货周期（天） |
| `score_basis_amount` | `bigint` | 是 | NULL | 近180天已确认样本金额（分），不足样本时为空 |
| `score_status` | `varchar(16)` | 否 | 'NOT_READY' | 评分状态：NOT_READY 样本不足；READY 可参与评分 |
| `status` | `tinyint` | 否 | '1' | 状态：1启用，0禁用 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `updated_by_id` | `bigint` | 是 | NULL | 最后维护人ID |
| `updated_by_name` | `varchar(100)` | 否 | '' | 最后维护人姓名 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_supplier_code` (`supplier_code`)``
- ``KEY `idx_supplier_name` (`supplier_name`)``
- ``KEY `idx_supplier_score` (`overall_score`)``
- ``KEY `idx_supplier_deleted_status` (`deleted`,`status`)``
- ``KEY `idx_supplier_score_ready` (`deleted`,`status`,`score_status`,`overall_score` DESC)``
- ``CONSTRAINT `chk_supplier_score_basis_amount` CHECK ((`score_basis_amount` >= 0))``
- ``CONSTRAINT `chk_supplier_score_direct_cutover_complete` CHECK ((1 = 1))``
- ``CONSTRAINT `chk_supplier_score_range` CHECK ((((`overall_score` is null) or (`overall_score` between 0 and 10000)) and ((`delivery_score` is null) or (`delivery_score` between 0 and 10000)) and ((`quality_score` is null) or (`quality_score` between 0 and 10000)) and ((`price_score` is null) or (`price_score` between 0 and 10000)) and ((`service_score` is null) or (`service_score` between 0 and 10000))))``
- ``CONSTRAINT `chk_supplier_score_status` CHECK ((`score_status` in (_utf8mb4'NOT_READY',_utf8mb4'READY')))``
- ``CONSTRAINT `chk_supplier_service_score_reason` CHECK ((((`service_score` is null) and (`service_score_reason` = _utf8mb4'')) or ((`service_score` is not null) and (char_length(trim(`service_score_reason`)) > 0))))``

## `supplier_product`

表用途：供应商供货产品表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 供应商供货产品ID |
| `supplier_id` | `bigint` | 否 | — | 供应商ID |
| `product_id` | `bigint` | 否 | — | 产品ID |
| `latest_purchase_price` | `bigint` | 是 | NULL | 由已确认入库事实维护的最近成交单价，单位分 |
| `quoted_purchase_price` | `bigint` | 是 | NULL | 人工有效报价，单位分 |
| `quoted_price_reason` | `varchar(500)` | 否 | '' | 当前人工有效报价原因；报价为空时为空字符串 |
| `quoted_price_updated_at` | `datetime` | 是 | NULL | 报价最近维护时间 |
| `quote_valid_until` | `date` | 是 | NULL | 报价有效截止日 |
| `min_order_qty` | `bigint` | 否 | '0' | 最小起订量，放大100倍保存，1000表示10.00 |
| `quality_score` | `int` | 是 | NULL | 供应产品质量评分，INT×100，NULL表示样本不足 |
| `price_score` | `int` | 是 | NULL | 供应产品价格评分，INT×100，NULL表示样本不足 |
| `recommend_score` | `int` | 是 | NULL | 供应产品推荐评分，INT×100，NULL表示样本不足 |
| `last_purchase_at` | `datetime` | 是 | NULL | 当前最近成交价对应的最后一次已确认采购入库时间 |
| `avg_delivery_days` | `decimal(10,2)` | 是 | NULL | 最近180天完全入库采购单的金额加权平均到货周期（天），仅供分析 |
| `score_basis_amount` | `bigint` | 是 | NULL | 近180天已确认样本金额（分），不足样本时为空 |
| `score_status` | `varchar(16)` | 否 | 'NOT_READY' | 评分状态：NOT_READY 样本不足；READY 可参与评分 |
| `status` | `tinyint` | 否 | '1' | 状态：1启用，0禁用 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `updated_by_id` | `bigint` | 是 | NULL | 最后维护人ID |
| `updated_by_name` | `varchar(100)` | 否 | '' | 最后维护人姓名 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_supplier_product` (`supplier_id`,`product_id`)``
- ``KEY `idx_supplier_product_product` (`product_id`)``
- ``KEY `idx_supplier_product_deleted_status` (`deleted`,`status`)``
- ``KEY `idx_supplier_product_ready` (`product_id`,`deleted`,`status`,`score_status`,`recommend_score` DESC)``
- ``KEY `idx_supplier_product_quote_valid` (`product_id`,`deleted`,`status`,`quote_valid_until`)``
- ``CONSTRAINT `chk_supplier_product_quote` CHECK ((((`quoted_purchase_price` is null) and (`quoted_price_reason` = _utf8mb4'') and (`quoted_price_updated_at` is null) and (`quote_valid_until` is null)) or ((`quoted_purchase_price` > 0) and (char_length(trim(`quoted_price_reason`)) > 0) and (`quoted_price_updated_at` is not null) and (`quote_valid_until` is not null))))``
- ``CONSTRAINT `chk_supplier_product_score_basis_amount` CHECK ((`score_basis_amount` >= 0))``
- ``CONSTRAINT `chk_supplier_product_score_range` CHECK ((((`quality_score` is null) or (`quality_score` between 0 and 10000)) and ((`price_score` is null) or (`price_score` between 0 and 10000)) and ((`recommend_score` is null) or (`recommend_score` between 0 and 10000))))``
- ``CONSTRAINT `chk_supplier_product_score_status` CHECK ((`score_status` in (_utf8mb4'NOT_READY',_utf8mb4'READY')))``

## `supplier_score_change_log`

表用途：供应商评分变化日志

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 评分变化日志ID |
| `change_key` | `char(64) COLLATE ascii_general_ci` | 否 | — | 幂等键 |
| `batch_no` | `varchar(64)` | 否 | — | 重算批次号；人工操作也必须生成批次号 |
| `rule_version` | `varchar(32)` | 否 | — | 评分规则版本 |
| `supplier_id` | `bigint` | 否 | — | 供应商ID |
| `supplier_product_id` | `bigint` | 是 | NULL | 供应商供货产品ID；供应商维度指标为空 |
| `metric_type` | `varchar(16)` | 否 | — | 指标类型：PRICE、QUALITY、DELIVERY、SERVICE；仅衍生校正在原因中说明 |
| `metric_score_before` | `int` | 是 | NULL | 指标变更前评分，INT×100 |
| `metric_score_after` | `int` | 是 | NULL | 指标变更后评分，INT×100 |
| `product_recommend_score_before` | `int` | 是 | NULL | 产品推荐分变更前，INT×100 |
| `product_recommend_score_after` | `int` | 是 | NULL | 产品推荐分变更后，INT×100 |
| `supplier_overall_score_before` | `int` | 是 | NULL | 供应商综合分变更前，INT×100 |
| `supplier_overall_score_after` | `int` | 是 | NULL | 供应商综合分变更后，INT×100 |
| `trigger_type` | `varchar(32)` | 否 | — | 触发来源：PRICE_TRIGGER、SERVICE_TRIGGER、INBOUND_TRIGGER、QUOTE_EXPIRED_TRIGGER、DAILY_TRIGGER、MERGED |
| `related_sources` | `json` | 否 | (json_array() | 完整来源数组：businessType、字符串businessId、同对象businessNo；无来源为空数组 |
| `operator_type` | `varchar(16)` | 否 | 'USER' | 操作人类型:USER-人工操作,SYSTEM-系统任务(如 MQ Consumer、D1/D2 定时任务、合并重算) |
| `operator_id` | `bigint` | 是 | NULL | 操作人ID；系统任务为空 |
| `operator_name` | `varchar(100)` | 否 | '' | 操作人名称：USER 填真实姓名，SYSTEM 填场景说明 |
| `reason` | `varchar(600)` | 否 | '' | 人工调整原因或系统计算说明；请求原因最多500字，额外保留校正说明 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_supplier_score_change_key` (`change_key`)``
- ``KEY `idx_supplier_score_log_supplier_time` (`supplier_id`,`create_time` DESC)``
- ``KEY `idx_supplier_score_log_product_time` (`supplier_product_id`,`create_time` DESC)``
- ``KEY `idx_supplier_score_log_batch` (`batch_no`)``
- ``KEY `idx_supplier_score_log_operator_type` (`operator_type`,`create_time` DESC)``
- ``CONSTRAINT `chk_supplier_score_log_metric_type` CHECK ((`metric_type` in (_utf8mb4'PRICE',_utf8mb4'QUALITY',_utf8mb4'DELIVERY',_utf8mb4'SERVICE')))``
- ``CONSTRAINT `chk_supplier_score_log_operator_identity` CHECK (((char_length(trim(`operator_name`)) > 0) and (((`operator_type` = _utf8mb4'USER') and (`operator_id` is not null)) or ((`operator_type` = _utf8mb4'SYSTEM') and (`operator_id` is null)))))``
- ``CONSTRAINT `chk_supplier_score_log_operator_type` CHECK ((`operator_type` in (_utf8mb4'USER',_utf8mb4'SYSTEM')))``
- ``CONSTRAINT `chk_supplier_score_log_score_range` CHECK ((((`metric_score_before` is null) or (`metric_score_before` between 0 and 10000)) and ((`metric_score_after` is null) or (`metric_score_after` between 0 and 10000)) and ((`product_recommend_score_before` is null) or (`product_recommend_score_before` between 0 and 10000)) and ((`product_recommend_score_after` is null) or (`product_recommend_score_after` between 0 and 10000)) and ((`supplier_overall_score_before` is null) or (`supplier_overall_score_before` between 0 and 10000)) and ((`supplier_overall_score_after` is null) or (`supplier_overall_score_after` between 0 and 10000))))``
- ``CONSTRAINT `chk_supplier_score_log_sources` CHECK ((json_type(`related_sources`) = _utf8mb4'ARRAY'))``
- ``CONSTRAINT `chk_supplier_score_log_trigger_type` CHECK ((`trigger_type` in (_utf8mb4'PRICE_TRIGGER',_utf8mb4'SERVICE_TRIGGER',_utf8mb4'INBOUND_TRIGGER',_utf8mb4'QUOTE_EXPIRED_TRIGGER',_utf8mb4'DAILY_TRIGGER',_utf8mb4'MERGED')))``

## `purchase_order`

表用途：采购订单主表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 采购订单ID |
| `purchase_no` | `varchar(64)` | 否 | — | 采购单号 |
| `supplier_id` | `bigint` | 否 | — | 供应商ID |
| `supplier_code` | `varchar(64)` | 否 | — | 供应商编码冗余 |
| `supplier_name` | `varchar(200)` | 否 | — | 供应商名称冗余 |
| `warehouse_id` | `bigint` | 否 | — | 目标入库仓库ID |
| `warehouse_name` | `varchar(100)` | 否 | — | 目标入库仓库名称冗余 |
| `status` | `varchar(32)` | 否 | 'DRAFT' | 状态：DRAFT、SUBMITTED、APPROVED、PARTIAL_INBOUND、INBOUND_DONE、CANCELLED |
| `total_amount` | `bigint` | 否 | '0' | 订单总金额，放大100倍保存，84480表示844.80 |
| `expected_arrival_date` | `date` | 是 | NULL | 预计到货日期，提交和审核前必须非空 |
| `created_by_id` | `bigint` | 是 | NULL | 创建人ID |
| `created_by_name` | `varchar(100)` | 否 | '' | 创建人姓名 |
| `submitted_at` | `datetime` | 是 | NULL | 提交时间 |
| `submitted_by_id` | `bigint` | 是 | NULL | 提交人ID |
| `submitted_by_name` | `varchar(100)` | 否 | '' | 提交人姓名 |
| `approved_by_id` | `bigint` | 是 | NULL | 审核人ID |
| `approved_by_name` | `varchar(100)` | 否 | '' | 审核人姓名 |
| `approved_at` | `datetime` | 是 | NULL | 审核时间 |
| `fully_received_at` | `datetime` | 是 | NULL | 使整张采购单完全入库的最后一笔入库确认时间 |
| `cancelled_at` | `datetime` | 是 | NULL | 取消时间 |
| `cancel_reason` | `varchar(500)` | 否 | '' | 取消原因 |
| `cancel_affects_delivery_score` | `tinyint` | 否 | '0' | 是否计入供应商交付扣分：供应商责任为1，非供应商责任为0 |
| `cancelled_by_id` | `bigint` | 是 | NULL | 取消人ID |
| `cancelled_by_name` | `varchar(100)` | 否 | '' | 取消人名称 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `deleted` | `tinyint` | 否 | '0' | 逻辑删除：0正常，1删除 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``UNIQUE KEY `uk_purchase_order_no` (`purchase_no`)``
- ``KEY `idx_purchase_order_supplier` (`supplier_id`)``
- ``KEY `idx_purchase_order_warehouse` (`warehouse_id`)``
- ``KEY `idx_purchase_order_status` (`status`)``
- ``KEY `idx_purchase_order_create_time` (`create_time`)``
- ``KEY `idx_purchase_order_deleted_status` (`deleted`,`status`)``
- ``KEY `idx_purchase_order_supplier_due` (`supplier_id`,`deleted`,`expected_arrival_date`,`status`)``
- ``KEY `idx_purchase_order_supplier_received` (`supplier_id`,`deleted`,`status`,`fully_received_at`)``
- ``CONSTRAINT `chk_purchase_order_cancel_affects_score` CHECK ((`cancel_affects_delivery_score` in (0,1)))``

## `purchase_order_item`

表用途：采购订单明细表

| 字段 | 类型 | 允许 NULL | 默认值 / 自动生成 | 字段说明 |
|---|---|---|---|---|
| `id` | `bigint` | 否 | — | 明细ID |
| `purchase_order_id` | `bigint` | 否 | — | 采购订单ID |
| `purchase_no` | `varchar(64)` | 否 | — | 采购单号冗余 |
| `supplier_product_id` | `bigint` | 是 | NULL | 供应商供货产品ID |
| `product_id` | `bigint` | 否 | — | 产品ID |
| `product_code` | `varchar(64)` | 否 | — | 产品编码冗余 |
| `product_name` | `varchar(200)` | 否 | — | 产品名称冗余 |
| `unit_name` | `varchar(32)` | 否 | '件' | 单位名称冗余 |
| `quantity_precision` | `tinyint` | 否 | '0' | 数量小数位快照：0-2，下单时从 product.quantity_precision 固化 |
| `quantity` | `bigint` | 否 | '0' | 采购数量，放大100倍保存，2400表示24.00 |
| `inbound_qty` | `bigint` | 否 | '0' | 已入库数量，放大100倍保存，900表示9.00 |
| `unit_price` | `bigint` | 否 | '0' | 采购单价，放大100倍保存，3520表示35.20 |
| `total_amount` | `bigint` | 否 | '0' | 明细金额，放大100倍保存，168960表示1689.60 |
| `selected_supplier_score` | `int` | 是 | NULL | 下单时由后端写入的供应产品推荐分快照，INT×100，NULL表示当时不可计算 |
| `create_time` | `datetime` | 否 | CURRENT_TIMESTAMP | 创建时间 |
| `update_time` | `datetime` | 否 | CURRENT_TIMESTAMP / ON UPDATE CURRENT_TIMESTAMP | 更新时间 |
| `remark` | `varchar(500)` | 否 | '' | 备注 |
| `version` | `int` | 否 | '0' | 乐观锁版本号 |

索引与约束：

- ``PRIMARY KEY (`id`)``
- ``KEY `idx_purchase_order_item_order` (`purchase_order_id`)``
- ``KEY `idx_purchase_order_item_product` (`product_id`)``
- ``KEY `idx_purchase_order_item_supplier_product` (`supplier_product_id`)``
- ``CONSTRAINT `chk_purchase_order_item_quantity_precision` CHECK ((`quantity_precision` between 0 and 2))``
- ``CONSTRAINT `chk_purchase_order_item_selected_supplier_score` CHECK (((`selected_supplier_score` is null) or (`selected_supplier_score` between 0 and 10000)))``
