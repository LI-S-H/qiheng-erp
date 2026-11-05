-- 退出旧节点工作窗口及其测试备份，保留原始记忆表和经验表结构。
-- 适用：已执行旧节点记忆迁移的 MySQL 8 数据库；可重复执行。
-- ai_node_memory 的测试流水需单独按环境清理，不在通用迁移中清空。
USE erp;

DROP TABLE IF EXISTS ai_node_working_memory;
DROP TABLE IF EXISTS ai_node_working_memory_bak_20261023;
