-- 移除会话表上的运行进度快照列。
-- 背景：运行时状态（当前执行到哪个节点）在 Redis 检查点里本来就有唯一来源，
-- 会话表再存一份会出现两份状态互相漂移，因此改由检查点推导，不再冗余落库。
-- 对应关系：019_ai_conversation_run_progress.sql 先加过这四列，本脚本把它们撤掉。
-- 执行前备份开发库；DDL 隐式提交。

USE erp;

ALTER TABLE ai_conversation
    DROP COLUMN run_node,
    DROP COLUMN run_label,
    DROP COLUMN run_started_at,
    DROP COLUMN run_updated_at;
