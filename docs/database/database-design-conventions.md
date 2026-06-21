# 数据库设计全局约定

## 基础约定

- 数据库使用 MySQL 8。
- 主键统一使用 `bigint`，由 MyBatis-Plus `ASSIGN_ID` 生成。
- 表名使用小写下划线。
- 状态字段优先使用 `status tinyint`，`1` 表示启用/正常，`0` 表示禁用/停用。
- 主数据、配置表和可删除的业务主表使用 `deleted tinyint`，`0` 表示正常，`1` 表示删除。
- 关系表、订单明细表、库存余额表、入库/出库作业明细、库存流水表、审计日志表默认不使用 `deleted`，避免历史链路和审计追溯被软删除语义干扰；这类数据需要作废时优先通过主表 `status` 或业务状态表达。
- MVP 阶段不强制创建物理外键，关系由业务层和索引保证。
- 所有表默认包含 `create_time` 和 `update_time`。创建时间使用 `DEFAULT CURRENT_TIMESTAMP`，更新时间使用 `DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP`，避免业务代码手动填普通审计时间。

时间字段约定：

```sql
create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
```

业务动作时间不使用自动更新时间代替，例如 `confirmed_at`、`approved_at`、`submitted_at`、`last_login_at`，这些字段由对应业务动作显式写入。

## 数值字段约定

金额字段使用 `decimal(18,2)`：

- 单价
- 总金额
- 参考采购价
- 参考销售价

数量字段使用 `decimal(18,4)`：

- 库存数量
- 锁定库存数量
- 采购数量
- 入库数量
- 合格数量
- 不合格数量

天数、周期等需要保留小数的统计字段可以使用 `decimal(10,2)`：

- 平均交付天数
- 平均周转天数

## 评分和百分率字段约定

评分、推荐分、百分率、命中率、准确率、合格率、准时率等字段，数据库统一使用 `int` 存放大 100 倍后的整数，不使用 `decimal(5,2)`。

这样做是为了避免 Java 实体、DTO、JSON 序列化和前端展示过程中出现小数转换、舍入和精度处理问题。

示例：

| 业务展示值 | 数据库存储值 |
|---|---:|
| 100.00 | 10000 |
| 89.75 | 8975 |
| 1.25 | 125 |
| 0.00 | 0 |

命名建议：

- 评分字段：`xxx_score int`
- 推荐分：`ai_score int`
- 百分率字段：`xxx_rate int`
- 快照分：`xxx_score_snapshot int` 或 `selected_xxx_score int`

接口输出时再除以 100 展示；接口入参如果接收小数展示值，也应在 Service 层统一转换成整数后落库。

## 适用边界

这个约定只适用于评分和百分率类字段。

以下字段仍然使用 `decimal`：

- 金额
- 单价
- 数量
- 重量
- 库存
- 平均天数等非百分率统计值
