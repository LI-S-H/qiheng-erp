-- 补齐仓管员的 AI 助手入口权限。
--
-- 背景：020_ai_assistant_permission.sql 只给「原本就引用过 ai 权限码」的角色追加了
-- ai:assistant:use，WAREHOUSE_STAFF 当时没有 ai 权限码，因此被漏掉——
-- 它进不了 AI 智能助手页面（@SaCheckPermission("ai:assistant:use")）。
--
-- 仓管员恰恰是 AI 的主要用户之一：他最需要问的就是「这个仓还剩多少可用库存」
-- 「这批货什么时候到」这类问题，而库存口径（可用 = stock_qty - locked_qty）
-- 正是 AI 需要业务规则文档才能讲清楚的部分。
--
-- 执行前建议备份 sys_role；本脚本只做追加，不删除任何权限码。
-- 重复执行安全：已含 ai:assistant:use 的角色会被跳过。

USE erp;

UPDATE sys_role
SET permission_codes = JSON_ARRAY_APPEND(permission_codes, '$', 'ai:assistant:use'),
    update_time = NOW()
WHERE role_code = 'WAREHOUSE_STAFF'
  AND deleted = 0
  AND status = 1
  AND JSON_SEARCH(permission_codes, 'one', 'ai:assistant:use') IS NULL;

-- 核对：仓管员的权限码里应能看到 ai:assistant:use
-- SELECT role_code, permission_codes FROM sys_role WHERE role_code = 'WAREHOUSE_STAFF';
