# MVP 产品资料库表设计：极简版

## 设计目标

产品模块先服务于采购、销售、库存三条主流程，保证业务单据能引用产品、库存能按产品汇总、AI 后续能基于产品维度查询库存和采购状态。

## 简化原则

- MVP 只设计 2 张表：`product_category`、`product`。
- 暂不单独设计品牌表、单位表、SPU/SKU 多层结构。
- 品牌、单位、规格型号直接放在 `product` 表中。
- 采购价、销售价先作为参考价保存，真实采购价格以后以采购订单明细为准。
- 库存数量不放在产品表，后续由仓库库存模块维护。
- 评分和百分率字段如后续加入，统一遵守 `database-design-conventions.md`：用 `int` 存放大 100 倍后的整数。

## 表：product_category（产品分类表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 分类ID |
| parent_id | bigint | 上级分类ID，顶级为 0 |
| category_name | varchar(100) | 分类名称 |
| status | tinyint | 状态：1 启用，0 禁用 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |

关系说明：`product.category_id` 关联本表。MVP 阶段分类只做基础分组，不做复杂分类属性。

## 表：product（产品表）

| 字段 | 类型 | 说明 |
|---|---|---|
| id | bigint PK | 产品ID |
| product_code | varchar(64) | 产品编码，唯一 |
| product_name | varchar(200) | 产品名称 |
| category_id | bigint | 产品分类ID |
| category_name | varchar(100) | 分类名称，冗余 |
| brand_name | varchar(100) | 品牌名称 |
| unit_name | varchar(32) | 单位名称，如 件、箱、kg |
| specification | varchar(255) | 规格型号 |
| barcode | varchar(64) | 条码，可为空 |
| reference_purchase_price | decimal(18,2) | 参考采购价 |
| reference_sale_price | decimal(18,2) | 参考销售价 |
| safety_stock_qty | decimal(18,4) | 安全库存数量，库存预警和采购建议可先参考 |
| status | tinyint | 状态：1 启用，0 禁用 |
| created_at | datetime | 创建时间 |
| updated_at | datetime | 更新时间 |
| deleted | tinyint | 逻辑删除 |
| remark | varchar(500) | 备注 |

关系说明：采购订单明细、销售订单明细、库存表后续都通过 `product_id` 关联本表，同时冗余 `product_code`、`product_name`、`unit_name`，减少列表查询联表。

## 表间关系

- `product.category_id` -> `product_category.id`

## MVP 业务规则

- 产品编码 `product_code` 必须唯一，用于导入、查询和业务单据展示。
- 产品停用后不能新增采购、销售单据，但历史单据仍保留产品快照字段。
- 库存数量不从产品表读取，后续统一从库存模块读取。
- 安全库存只是基础配置，后续 AI 采购建议可以结合库存、销量、供应商交付表现再计算。

## 测试场景

- 可以新增、编辑、停用产品分类。
- 可以新增产品并选择分类。
- 产品列表可以按产品编码、名称、分类、状态查询。
- 已停用产品不能被新采购单或销售单选择。
- 库存、采购、销售模块可以通过 `product_id` 引用产品。
