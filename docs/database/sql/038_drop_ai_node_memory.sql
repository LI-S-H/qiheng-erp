-- 旧节点记忆原文表退出：表内无数据、无写入链路，智能体执行的原始记录改由 ai_agent_trace 承载。
-- 不可逆操作：生产环境执行前需确认该表已备份。
USE erp;

DROP TABLE IF EXISTS ai_node_memory;
