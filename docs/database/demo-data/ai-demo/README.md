# AI 演示数据

本目录提供一套可直接导入的 AI 会话演示数据，配合 `docs/database/demo-data/demo-data-bundle.zip` 里的业务数据使用。

## 内容

| 文件/目录 | 内容 |
|---|---|
| `ai-demo-data.sql` | 12 个精选会话及其消息、执行轨迹、数据集、分析结果、报告；46 条已发布经验；2 条经验目录摘要；29 条业务图谱关系 |
| `files/datasets/` | 会话引用的数据集文件（102 个），导入后放到后端数据集目录 |
| `files/results/` | 分析结果文件（75 个，保留用户/会话/任务目录层级），导入后放到后端结果目录 |

## 覆盖场景

- 库存健康评估（积压与缺货风险）
- 补货与安全库存缺口
- 供应商选择与价格比较
- 订单履约与交付风险
- 退货原因分析
- 库存运营分析报告（2 份）
- 采购草稿生成、修改、提交与取消

## 导入顺序

1. 完成 `docs/database/init` 的 `01-schema.sql`、`02-system-demo.sql`、`03-ai-metadata.sql`。
2. 导入业务演示数据（`demo-data-bundle.zip` 内的 SQL），并按其说明同步 Redis 编号序列。
3. 导入本目录 SQL：

   ```bash
   mysql --user "$DB_USER" --password "$DB_NAME" < ai-demo-data.sql
   ```

4. 复制文件，保持目录结构：
   - `files/datasets/*` → 后端数据集目录（默认 `erp-server/data/ai-datasets/`）
   - `files/results/*` → 后端分析结果目录（默认 `erp-server/data/ai-python-results/`）
5. 重启后端或等待缓存刷新，用演示账号（admin）打开 AI 助手查看会话。

## 注意

- 会话归属用户为 `1900000000000000001`（admin），与 `02-system-demo.sql` 一致；其他用户看不到这些会话。
- `ai-demo-data.sql` 里的关系数据会先清空 `ai_data_table_relation` 再写入，可重复执行；其余表为追加写入，重复导入会产生重复会话，建议只在空库导入一次。
- 数据集和分析结果文件缺失时，历史会话仍可查看，但打开数据集或读取分析结果会失败。
- 经验数据导入后，经验目录摘要需要等待下一次经验任务刷新或手动触发。
- 本数据由开发库导出，仅用于隔离环境的演示和测试，不代表生产数据。