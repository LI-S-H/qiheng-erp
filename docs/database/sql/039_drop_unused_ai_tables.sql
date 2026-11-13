-- 早期设计的三张 AI 表未实施：开发库从未建表，代码零引用，不再保留在现行表清单中。
-- 可重复执行；生产环境若已建表同样清理。
USE erp;

DROP TABLE IF EXISTS ai_document;
DROP TABLE IF EXISTS ai_document_chunk;
DROP TABLE IF EXISTS ai_interaction_log;
