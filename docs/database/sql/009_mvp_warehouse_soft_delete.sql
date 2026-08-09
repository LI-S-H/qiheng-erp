-- 仓库出库单/入库单增加逻辑删除字段与索引
-- 日期:2026-08-09
-- 说明:为 outbound_bill/inbound_bill 增加 deleted 字段,支持 MyBatis-Plus @TableLogic 逻辑删除。
--       退货单取消等场景需要"软删"未确认工作单,避免仓库管理员看到 CANCELLED 状态误点。
--       MyBatis-Plus @TableLogic 会为所有查询自动注入 deleted=0,必须建索引避免全表扫描。
--       索引设计参考 return_order 的 idx_return_order_deleted_status(deleted, status)。

-- 1. 加列
ALTER TABLE outbound_bill ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1删除';
ALTER TABLE inbound_bill ADD COLUMN deleted TINYINT NOT NULL DEFAULT 0 COMMENT '逻辑删除:0正常,1删除';

-- 2. 加索引(MyBatis-Plus @TableLogic 自动带 deleted=0 的查询必须走索引)
ALTER TABLE outbound_bill ADD INDEX idx_outbound_bill_deleted_status (deleted, status);
ALTER TABLE inbound_bill ADD INDEX idx_inbound_bill_deleted_status (deleted, status);
