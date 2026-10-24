-- 工作记忆表改为纯文本转写。
-- 背景：节点记忆分两张表，职责原本混在一起。
--   ai_node_memory          流水记忆：结构化事件 JSON，只增不改，供审计与追溯。
--   ai_node_working_memory  工作记忆：节点读取历史的唯一入口。
-- 改造后：工作记忆存「模型视角的纯文本转写」——一行 = 模型的一条消息，
-- 首行标记说明角色（[输入] / [输出] / [摘要]），正文与真正发给模型的内容一字不差。
-- 流水表一个字不动，它仍然是审计语言。
--
-- 处理：
--   1. 去掉 content 的 JSON_VALID 约束：纯文本不是 JSON，约束会直接拒写。
--   2. 清空一次工作记忆表：旧行是事件 JSON 格式，与新的读取协议不兼容。
--      工作记忆是可再生窗口，清空不丢信息——审计原文完整保留在 ai_node_memory。
--   3. 同步列注释，说明新语义。
--
-- 影响面：只改工作记忆表的约束、数据与注释，不动流水表、不动表结构与索引。
-- 新库执行 018 建表后同样要执行本脚本（018 建出的表还带着 JSON_VALID 约束）。
-- 执行前备份开发库；DDL 隐式提交。本脚本只执行一次。

USE erp;

-- 纯文本转写不再是 JSON，去掉 JSON_VALID 约束
ALTER TABLE ai_node_working_memory
    DROP CHECK chk_ai_node_working_content;

-- 旧行是事件 JSON 格式，新读取协议认不出来：清空一次
DELETE FROM ai_node_working_memory;

-- 列注释与表注释同步
ALTER TABLE ai_node_working_memory
    MODIFY COLUMN content longtext NOT NULL
        COMMENT '纯文本转写：模型视角的一次输入或一次输出，首行为 [输入]/[输出]/[摘要] 标记',
    COMMENT = 'AI节点工作记忆表（节点读取入口，纯文本转写，可压缩）';