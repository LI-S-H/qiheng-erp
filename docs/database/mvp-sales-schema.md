# MVP 客户销售库表设计：极简版

## 设计目标

销售模块先支撑客户维护、销售订单创建、库存锁定、销售出库溯源和销售汇总查询。MVP 阶段不在销售模块单独设计销售出库单表，销售出库统一使用仓库模块的 `outbound_bill` / `outbound_bill_item`，类型为 `SALES_OUT`；确认后再生成库存流水 `stock_bill` / `stock_bill_item`。

## 简化原则

- MVP 设计 3 张表：`customer`、`sales_order`、`sales_order_item`。
- 销售出库不在销售模块单独建表，统一使用仓库模块 `outbound_bill` / `outbound_bill_item`。
- 销售订单主表冗余客户、仓库名称，明细冗余产品信息，减少列表查询联表。
- 销售退货后续复用仓库模块 `SALES_RETURN` 入库单；如果退货流程复杂，再补销售退货单表。
- MVP 暂不设计收款单、发票、对账、复杂价格策略、审批流。
- 评分和百分率字段如后续加入，统一遵守 `database-design-conventions.md`：用 `int` 存放大 100 倍后的整数；数量统一为 `bigint` 存放大 100 倍后的整数。
- 销售订单明细是订单事实明细，不使用 `deleted`；删除草稿明细时直接物理删除，已审核订单通过订单状态控制。

## 表：customer（客户表）

| 字段            | 类型            | 说明                 |
| ------------- | ------------- | ------------------ |
| id            | bigint PK     | 客户ID               |
| customer_code | varchar(64)   | 客户编码，唯一            |
| customer_name | varchar(200)  | 客户名称               |
| contact_name  | varchar(100)  | 联系人                |
| contact_phone | varchar(32)   | 联系电话               |
| address       | varchar(255)  | 地址                 |
| credit_limit  | bigint        | 信用额度，按分（×100）保存，18000000表示180000.00，MVP 仅记录不做强拦截 |
| status        | tinyint       | 状态：1 启用，0 禁用       |
| create_time   | datetime      | 创建时间               |
| update_time   | datetime      | 更新时间               |
| updated_by_id | bigint        | 最后维护人ID，新建/编辑/启停时由后端写入，不允许为空       |
| updated_by_name | varchar(100) | 最后维护人姓名，新建/编辑/启停时由后端写入，不允许为空     |
| deleted       | tinyint       | 逻辑删除               |
| remark        | varchar(500)  | 备注                 |

关系说明：`sales_order.customer_id` 关联本表。客户手机号属于敏感字段，后续字段权限完善后需要支持脱敏。

## 表：sales_order（销售订单主表）

| 字段                     | 类型            | 说明                                                                               |
| ---------------------- | ------------- | -------------------------------------------------------------------------------- |
| id                     | bigint PK     | 销售订单ID                                                                           |
| sales_no               | varchar(64)   | 销售单号，唯一                                                                          |
| customer_id            | bigint        | 客户ID                                                                             |
| customer_code          | varchar(64)   | 客户编码，冗余                                                                          |
| customer_name          | varchar(200)  | 客户名称，冗余                                                                          |
| warehouse_id           | bigint        | 出库仓库ID                                                                           |
| warehouse_name         | varchar(100)  | 出库仓库名称，冗余                                                                        |
| status                 | varchar(32)   | 状态：`DRAFT`、`SUBMITTED`、`APPROVED`、`PARTIAL_OUTBOUND`、`OUTBOUND_DONE`、`CANCELLED` |
| total_amount           | bigint        | 订单总金额，按分（×100）保存，0表示0.00                                                                            |
| expected_delivery_date | date          | 预计发货日期                                                                           |
| locked_at              | datetime      | 库存锁定时间                                                                           |
| created_by_id          | bigint        | 创建人ID                                                                            |
| created_by_name        | varchar(100)  | 创建人姓名                                                                            |
| submitted_at           | datetime      | 提交时间                                                                             |
| submitted_by_id        | bigint        | 提交人ID，历史数据可为空                                                                    |
| submitted_by_name      | varchar(100)  | 提交人姓名，历史数据可为空                                                                    |
| approved_by_id         | bigint        | 审核人ID                                                                            |
| approved_by_name       | varchar(100)  | 审核人姓名                                                                            |
| approved_at            | datetime      | 审核时间                                                                             |
| create_time            | datetime      | 创建时间                                                                             |
| update_time            | datetime      | 更新时间                                                                             |
| deleted                | tinyint       | 逻辑删除                                                                             |
| remark                 | varchar(500)  | 备注                                                                               |

关系说明：销售订单审核后，可生成仓库模块 `outbound_bill`，其中 `source_type = SALES_ORDER`，`source_id = sales_order.id`，`source_no = sales_order.sales_no`，并快照客户、出库仓库、销售数量和累计已出库数量。待确认出库单的本次出库数量初始为 0 或空业务值，由仓库人员按实物发货填写；剩余未出库数量由后端按 `销售数量 - 累计已出库数量 - 本次出库数量` 计算，不允许前端或用户手动维护。

> 当前销售 Java 后端尚未生成。本次已固化字段、前端展示和接口契约；后续实现时，提交操作必须写入 `submitted_by_id/submitted_by_name`，客户创建、编辑、启停必须写入 `updated_by_id/updated_by_name`。

## 表：sales_order_item（销售订单明细表）

| 字段             | 类型            | 说明      |
| -------------- | ------------- | ------- |
| id             | bigint PK     | 明细ID    |
| sales_order_id | bigint        | 销售订单ID  |
| sales_no       | varchar(64)   | 销售单号，冗余 |
| product_id     | bigint        | 产品ID    |
| product_code   | varchar(64)   | 产品编码，冗余 |
| product_name   | varchar(200)  | 产品名称，冗余 |
| unit_name      | varchar(32)   | 单位名称，冗余 |
| quantity_precision | tinyint    | 数量小数位快照：0-2，下单时从 product.quantity_precision 固化 |
| quantity       | bigint        | 销售数量，放大100倍保存，N 表示 N/100.00    |
| locked_qty     | bigint        | 已锁定库存数量，放大100倍保存    |
| outbound_qty   | bigint        | 已出库数量，放大100倍保存   |
| unit_price     | bigint        | 销售单价，按分（×100）保存，0表示0.00    |
| total_amount   | bigint        | 明细金额，按分（×100）保存，0表示0.00    |
| create_time    | datetime      | 创建时间    |
| update_time    | datetime      | 更新时间    |
| remark         | varchar(500)  | 备注      |

关系说明：仓库出库单明细 `outbound_bill_item.source_item_id` 关联本表；出库确认后生成的 `stock_bill_item.business_source_item_id` 继续关联本表，用于从库存流水反查销售订单明细。

## 表间关系

- `sales_order.customer_id` -> `customer.id`
- `sales_order.warehouse_id` -> `warehouse.id`
- `sales_order_item.sales_order_id` -> `sales_order.id`
- `sales_order_item.product_id` -> `product.id`
- `outbound_bill.source_id` -> `sales_order.id`，当 `source_type = SALES_ORDER`
- `outbound_bill_item.source_item_id` -> `sales_order_item.id`，当 `outbound_bill.outbound_type = SALES_OUT`
- `stock_bill.business_source_id` -> `sales_order.id`，当 `business_source_type = SALES_ORDER`
- `stock_bill_item.business_source_item_id` -> `sales_order_item.id`，当 `stock_bill.bill_type = SALES_OUT`

## MVP 业务规则

- 销售订单只能选择启用状态的客户和产品。
- 销售订单保存后不直接扣减库存。
- 提交或审核销售订单时，服务层校验可用库存 `warehouse_stock.stock_qty - warehouse_stock.locked_qty`。
- 库存锁定成功后，更新 `warehouse_stock.locked_qty` 和 `sales_order_item.locked_qty`。
- 销售订单审核后生成仓库模块 `SALES_OUT` 待确认出库单，不直接扣减库存；本次出库数量初始为 0 或空业务值。
- 前端状态名称统一按当前业务阶段显示：`DRAFT` 为“草稿”、`SUBMITTED` 为“待审核”、`APPROVED` 为“待出库”、`PARTIAL_OUTBOUND` 为“部分出库”、`OUTBOUND_DONE` 为“已出库”、`CANCELLED` 为“已取消”；筛选项、表格标签、详情和摘要不得再使用“已提交”“已审核”表达当前状态。
- 仓库人员按实物发货填写并确认本次出库数量后，生成 `stock_bill` 库存流水，扣减 `warehouse_stock.stock_qty` 和 `warehouse_stock.locked_qty`，并回写 `sales_order_item.outbound_qty`。
- 确认销售出库时，后端必须校验本次出库数量大于 0 且不超过来源明细剩余未出库数量；确认后的剩余未出库数量由后端计算，不作为前端提交字段。
- 当明细 `outbound_qty < quantity` 时订单为 `PARTIAL_OUTBOUND`，全部出库后为 `OUTBOUND_DONE`。
- 销售后端实现出库确认回写时，应由仓储模块在 `outbound_bill` 真实“出库确认”后调用销售来源端口；销售端在同一事务内累计 `sales_order_item.outbound_qty`、更新订单状态，并且仅当不存在待确认出库单且仍有剩余数量时生成下一张只含剩余数量的 `PENDING_CONFIRM` 出库单。编辑待确认单不生成新单，已确认单不得编辑。
- 取消未出库订单时，需要释放已锁定库存。
- 销售退货后续使用 `SALES_RETURN` 入库流水；如需退货申请、退款、质检等复杂流程，再补销售退货单表。

## 测试场景

- 可以新增、编辑、停用客户。
- 可以创建销售订单并选择客户、仓库和产品明细。
- 销售订单提交或审核时可以锁定库存。
- 库存不足时不能锁定库存。
- 审核销售订单后能生成 `SALES_OUT` 待确认出库单。
- 确认出库后能扣减库存、释放锁定库存，并回写销售明细已出库数量。
- 销售出库单和库存流水可以通过 `source_id/source_no`、`business_source_id/business_source_no` 反查销售订单。
- AI 销售汇总 Tool 可以按销售订单和销售明细统计销售金额、销售数量。

## 扩展点：部门数据范围权限（待后续实现）

> 本节为后续 feature 的扩展点说明，**MVP 阶段不实现**。目的是把"业务表如何承接部门归属"的口子先在文档里定下来，避免将来 feature 上线时改表结构对线上数据造成破坏。

### 销售订单的部门归属规则

`MVP` 阶段 `sales_order` / `sales_order_item` 不携带部门字段，**新增**部门数据范围权限 feature 时，将**在 `sales_order` 主表新增 `dept_id` 字段**，归属规则采用**创建人归属（快照式）**：

| 字段 | 取值 | 说明 |
| --- | --- | --- |
| `sales_order.dept_id` | 创建人在创建订单时所属部门的 ID | 取自 `sys_user.dept_id` 在订单创建时刻的快照值 |

**关键约束**：

- **快照式归属**：订单创建瞬间把 `created_by_id` 对应用户的 `sys_user.dept_id` 写入 `sales_order.dept_id`，**之后不随创建人调岗而变化**。这样历史订单的部门归属稳定可追溯。
- **变更父级不影响历史订单**：若后续需要把订单"迁"到新部门，应提供专门的"调整部门归属"接口（暂不设计），而不是直接覆盖。
- **创建人必须存在部门**：`sys_user.dept_id NOT NULL` 是硬约束，新建订单一定能拿到部门快照。
- **被删除用户创建的订单**：`deleted = 1` 的用户仍可能在 `created_by_id` 字段上留下历史订单，**不级联清理**，部门快照保留。
- **审核人不参与归属**：审核人（`approved_by_id`）不参与部门归属判定，只记录审批轨迹。

### 扩展后的字段表（计划，仅供参考）

```text
sales_order（追加字段，未落地）
├── dept_id  bigint NOT NULL DEFAULT 0  -- 部门 ID 快照，来自创建人 sys_user.dept_id
├── KEY idx_sales_order_dept (dept_id)  -- 配合 ancestors LIKE 过滤
```

**索引选择**：`ancestors` LIKE 查询的命中行最终会回表到 `sales_order.dept_id`，所以 `dept_id` 上必须有索引；普通等值查询也会用到。`ancestors` 自身在 `sys_dept` 表上**不需要**额外索引——因为 `LIKE '%,X,%'` 前缀是固定的 `%,`，MySQL 无法走 B+Tree 前缀索引，依赖全表扫描 + 内存过滤；`sys_dept` 是小表（几十到几百行），全表扫描代价可忽略。

### 启用数据范围权限后的查询模式

```sql
-- 1) 算"当前用户 + 所有下级"可见的部门 ID 集合
-- 假设用户 A 的 dept_id = '1900000000000000108'，数据范围 = 本部门及下级
SELECT id FROM sys_dept
WHERE id = '1900000000000000108'
   OR CONCAT(',', ancestors, ',') LIKE CONCAT('%,', '1900000000000000108', ',%');

-- 2) 把可见部门 ID 集合代入销售订单主查询
SELECT o.*
FROM sales_order o
WHERE o.dept_id IN ( <步骤 1 的结果集> )
  AND o.deleted = 0
  AND o.status IN ('APPROVED', 'PARTIAL_OUTBOUND', 'OUTBOUND_DONE')
ORDER BY o.create_time DESC
LIMIT ? OFFSET ?;
```

### 暂不设计的相关扩展

- 销售明细 `sales_order_item` 不单独存 `dept_id`，**直接 join 主表**即可。冗余存储会引入"主表改部门后明细未同步"的一致性维护成本，**禁止**。
- 客户 `customer`、产品 `product`、仓库 `warehouse` 不参与部门归属（它们是"业务实体"，归属的是"被谁维护/属于哪个组织"维度，**与"销售订单归属"无关**），因此**不**为它们预留 `dept_id` 字段。
- 仓库模块的 `stock_bill` 是否也按创建人归属部门，待与采购、库存模块一起评审后再决定（可能存在"销售出库单与采购入库单分属不同部门"的场景）。
