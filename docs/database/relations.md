# 数据库业务关系

依据 2026-11-21 的结构导出及当前业务代码整理。结构未声明数据库外键，下述是字段语义及业务服务使用的关联；不代表数据库会自动校验级联或引用完整性。各表字段和索引见[表结构](schema/README.md)。

| 业务 | 主要关联 |
|---|---|
| 分类和商品 | `product.category_id` 对应 `product_category.id`；分类的 `parent_id` 形成层级。 |
| 供应商和商品 | `supplier_product.supplier_id`、`product_id` 分别对应供应商和商品。 |
| 采购 | `purchase_order.supplier_id`、`warehouse_id` 对应供应商和仓库；`purchase_order_item.purchase_order_id` 对应订单，明细另关联商品及供货关系。 |
| 销售 | `sales_order.customer_id`、`warehouse_id` 对应客户和仓库；`sales_order_item.sales_order_id` 对应订单，明细关联商品。 |
| 退货 | `return_order.source_order_id`、`party_id` 按退货类型解释；明细通过 `return_order_id` 和 `source_order_item_id` 关联退货单及原单明细。 |
| 入出库 | 入出库单通过 `source_type`、`source_id` 表示来源，通过 `warehouse_id` 指定仓库；明细的 `source_item_id` 表示来源明细。具体目标表取决于来源类型。 |
| 库存流水 | `stock_bill.work_bill_id`、`business_source_id` 分别表示作业单和业务来源；明细关联流水单、作业明细、业务明细及商品。 |
| 库存余额 | `warehouse_stock.warehouse_id` 和 `product_id` 对应仓库与商品，余额与变更流水由仓储服务维护。 |
| 用户权限 | `sys_user.dept_id` 对应部门，`sys_user_role` 关联用户和角色；角色的权限编码及权限目录共同参与权限处理。 |
| AI | 会话、消息、数据集、分析结果和报告包含用户或会话标识；数据集及文件的访问范围依赖服务端校验。 |

不同来源类型、状态及历史数据的边界必须结合对应 Service 和接口核实；导入 SQL 不能替代业务服务的状态、数量、事务和权限校验。
