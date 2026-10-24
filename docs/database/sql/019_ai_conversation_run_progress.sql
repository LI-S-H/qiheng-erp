-- 会话运行进度快照：任务执行期间记录当前节点，供页面刷新后恢复进度展示。
-- 只更新会话行的字段，不新增行；任务结束时四个字段置空。
--
-- 已废弃：运行时状态改由 Redis 检查点推导，本文件新增的四列随后由
-- docs/database/sql/migrate_ai_conversation_drop_run_progress.sql 删除。
-- 保留本文件仅为记录迁移历史。
--
-- 执行前备份开发库；DDL 隐式提交。

USE erp;

ALTER TABLE ai_conversation
    ADD COLUMN run_node       VARCHAR(32) NULL COMMENT '当前运行节点编码，无运行为NULL' AFTER active_request_id,
    ADD COLUMN run_label      VARCHAR(64) NULL COMMENT '当前节点展示文案，如「正在规划」' AFTER run_node,
    ADD COLUMN run_started_at DATETIME    NULL COMMENT '本轮开始时间' AFTER run_label,
    ADD COLUMN run_updated_at DATETIME    NULL COMMENT '进度最后更新时间' AFTER run_started_at;
