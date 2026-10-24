-- 修复 AI 节点记忆三张表的注释乱码。
--
-- 现象：018_ai_node_memory.sql 建表时数据库连接字符集与文件编码不一致，
--   表注释与列注释以 UTF-8 字节写进了 latin1 字符集列，库里显示为乱码。
--   数据本身（id、content、时间戳等）没有受影响，只是注释不可读。
--
-- 处理：按 information_schema 当前的实际列定义，只替换 COMMENT，不改类型、可空、
--   默认值、主键与索引——脚本里的 MODIFY COLUMN 完整重述了当前定义，改动范围仅限注释。
--   列定义取自 information_schema 的 COLUMN_TYPE / IS_NULLABLE / COLUMN_DEFAULT / EXTRA。
--
-- 影响面：只改注释，不动数据、不动结构、不影响任何读写路径。
--   这三张表不对 AI 开放（不在 ai_data_table 清单里），但它们是运维排查表结构的依据，
--   注释乱码会让人工排查无从下手。
--
-- 新库不需要执行本脚本（018 建表就是对的）；已有库执行一次即可，重复执行结果相同。

USE erp;

-- 记忆原文表：只增不改，用于审计与追溯
ALTER TABLE `ai_node_memory`
  MODIFY COLUMN `id` bigint NOT NULL COMMENT '记忆ID',
  MODIFY COLUMN `conversation_id` bigint NOT NULL COMMENT '所属会话ID',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '所属用户ID，仅服务端从登录上下文写入',
  MODIFY COLUMN `node_code` varchar(32) NOT NULL COMMENT '节点编码：PLANNER、SQL_GENERATE、PYTHON_GENERATE、REPORTER、REFLECTION',
  MODIFY COLUMN `seq` bigint NOT NULL COMMENT '节点内递增序号，只增不改',
  MODIFY COLUMN `content` longtext NOT NULL COMMENT 'JSON原文，永不压缩改写；类别由其中eventType字段表示',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
  COMMENT = 'AI节点记忆原文表（只增不改）';

-- 工作记忆表：节点实际读取的入口，压缩时只改写它
ALTER TABLE `ai_node_working_memory`
  MODIFY COLUMN `id` bigint NOT NULL COMMENT '工作记忆ID',
  MODIFY COLUMN `conversation_id` bigint NOT NULL COMMENT '所属会话ID',
  MODIFY COLUMN `user_id` bigint NOT NULL COMMENT '所属用户ID',
  MODIFY COLUMN `node_code` varchar(32) NOT NULL COMMENT '节点编码',
  MODIFY COLUMN `sort_order` bigint NOT NULL COMMENT '节点内排序号；窗口压缩后在历史最大号之后写入，避免复用逻辑删除号',
  MODIFY COLUMN `content` longtext NOT NULL COMMENT 'JSON内容；类别由其中eventType字段表示',
  MODIFY COLUMN `source_memory_ids` json NULL COMMENT '压缩摘要条目记录来源原文ID数组，便于追溯',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
  COMMENT = 'AI节点工作记忆表（节点读取入口，可压缩）';

-- 长期经验表：跨任务沉淀，不与具体会话绑定
ALTER TABLE `ai_node_experience`
  MODIFY COLUMN `id` bigint NOT NULL COMMENT '经验ID',
  MODIFY COLUMN `node_code` varchar(32) NOT NULL COMMENT '归属节点编码',
  MODIFY COLUMN `experience_text` text NOT NULL COMMENT '经验正文，高度精简',
  MODIFY COLUMN `evidence_refs` json NULL COMMENT '可选证据引用',
  MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'CANDIDATE' COMMENT '状态：CANDIDATE、PUBLISHED、REVOKED',
  MODIFY COLUMN `effective_from` datetime NULL COMMENT '生效时间',
  MODIFY COLUMN `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  MODIFY COLUMN `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  MODIFY COLUMN `deleted` tinyint NOT NULL DEFAULT 0 COMMENT '逻辑删除：0正常，1删除',
  COMMENT = 'AI节点长期经验表';
