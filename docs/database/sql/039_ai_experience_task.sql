-- 已有数据库的增量结构；运行前先备份并确认不存在同名表。
-- 不回填历史任务，不改动已有经验、摘要和流水。
CREATE TABLE `ai_agent_experience_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '经验提取任务编号',
  `user_id` bigint NOT NULL COMMENT '所属用户',
  `conversation_id` bigint NOT NULL COMMENT '所属会话',
  `agent_type` varchar(32) NOT NULL COMMENT '智能体类型',
  `task_id` varchar(192) NOT NULL COMMENT '主任务运行 ID 或分析任务 ID',
  `status` varchar(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING、PROCESSING、COMPLETED、ERROR',
  `attempt_count` int NOT NULL DEFAULT 0 COMMENT '模型输出错误的重试次数',
  `create_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  `update_time` datetime(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_experience_task` (`user_id`,`conversation_id`,`agent_type`,`task_id`),
  KEY `idx_experience_pending` (`status`,`create_time`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 经验任务提取进度';
