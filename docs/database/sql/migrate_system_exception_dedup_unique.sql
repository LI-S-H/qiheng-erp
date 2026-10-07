-- 已部署库迁移：系统异常记录消费幂等唯一键。
-- Consumer 采用"直接插入 + 唯一键冲突跳过"的强幂等，替代先查后插：
-- source_module + source_no + error_code + occurred_at 四字段判定同一异常的重投消息。
-- 不含 error_message（最长 2000 字符），避免长文本进索引；同秒同来源同错误码的不同异常会少记，
-- 符合异常记录"宁可少记不错记"原则。NULL 列不参与唯一判重（MySQL 语义），发布端两处均必填。

ALTER TABLE system_exception
    ADD UNIQUE KEY uk_system_exception_dedup (source_module, source_no, error_code, occurred_at);
