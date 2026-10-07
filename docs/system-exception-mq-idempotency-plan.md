# 系统异常 MQ 事件幂等说明

## 标识与发送

- `eventId` 在 `publishSystemError()`、`publishJobFailure()` 创建消息时通过 `IdWorker.getIdStr()` 生成，以字符串传输。直接调用 `publish()` 的调用方也必须先赋值；发送层只校验，不补号、不改号、不加锁。缺失或非法值不发送，重发原消息保留原值。
- `exceptionNo` 仍由消费端使用 `BillNoGenerator` 生成 `SE` 展示编号；`sourceNo`、错误码和发生时间用于排查，不承担幂等职责。
- MQ Key 使用 `eventId` 便于检索，Tag 继续使用异常类型；Key 本身不能阻止重复消费。
- 保留框架异步发送、内建重试和最终失败日志的尽力上报方式，不引入 outbox，不能保证消息必达。

## 入库与并发

`system_exception.event_id` 为 `VARCHAR(20) CHARACTER SET ascii COLLATE ascii_bin NOT NULL`，唯一索引为 `uk_system_exception_event_id`。旧的四字段唯一索引必须移除，否则不同事件仍可能误合并。

消费者保持现有 `onMessage()` 结构，校验消息后生成 `SE` 编号并直接插入，不先查再插、不加 Redis 锁。只在异常因果链中识别到 MySQL 错误码 `1062`、SQLState `23000` 和完整事件索引名时 ACK 重复；其他唯一键冲突、连接失败或死锁继续抛出让 MQ 重试。

同事件并发写入由数据库唯一索引协调；同来源、同错误码、同秒的不同事件分别保存。重试可能多消耗 `SE` 序号，允许编号不连续。

## 开发库与部署约束

按用户要求不做历史迁移或回填，建表及种子数据以 `docs/database/sql/007_mvp_system_exception.sql` 为准；旧四字段索引迁移脚本已删除。`CREATE TABLE IF NOT EXISTS` 不会修改已有表。

本次用户已明确授权仅清空重建本地 `13307` 的 `system_exception` 表并测试。重建必须显式启用测试开关，校验本机数据库地址；不得删除其他业务表、共享 Redis 或业务 Topic。测试使用隔离 Topic 与消费组。

旧消息没有 `eventId` 时记录日志并跳过，消费者不随机补 ID。部署前需停止旧版本异常生产消费并确认旧积压处理方式，不擅自清理 MQ。

本地单实例复用项目雪花 ID 生成器。生产多实例必须配置不冲突的 workerId/datacenterId 并保持时间同步；默认节点推导不能当作绝对唯一保证。本次不更改全局业务主键配置。

## 测试与审阅顺序

1. `SystemExceptionRecordMessage.java`：字符串事件标识。
2. `SystemExceptionMqPublisher.java`：只生成一次、重发保留、Key与失败日志。
3. `SystemException.java`、`007_mvp_system_exception.sql`：字段、索引、种子数据。
4. `SystemExceptionRecordConsumer.java`：直接插入及严格的索引冲突识别。
5. `SystemExceptionMqPublisherTest.java`、`SystemExceptionRecordConsumerTest.java`：重发、非法ID、同秒不同事件、其他索引冲突、连接异常、时区。
6. `SystemExceptionRecordClosedLoopIT.java`：真实MQ与MySQL核对重复事件仅一条、不同事件同秒保留两条及并发消费。只有显式启用才写入，不将历史报告冒充本次通过。

前端继续显示异常编号与摘要，无需增加字段或铃铛接口。
