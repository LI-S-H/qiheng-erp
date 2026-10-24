-- 移除会话表上的 active_request_id。
-- 背景：该列原本用于拒绝过期异步回调，但回调路径与「当前执行轮次」的判定已经改由
-- Redis 检查点承担，这一列既没有被读取也没有被写入，属于会话表上的冗余运行时状态。
-- 对已经建过库的环境执行一次即可；按 006_mvp_ai.sql 全新建库不需要执行本脚本。
-- 执行前备份开发库；DDL 隐式提交。

USE erp;

ALTER TABLE ai_conversation
    DROP COLUMN active_request_id;
