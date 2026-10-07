# 评分重算 RocketMQ 配置与 Topic 初始化

Topic 是 Broker 上区分消息用途的名称；不是 Java 包，也不需要为每个 Topic 部署一套 MQ。
评分使用 `erp-supplier-score-recalc`，本次不注册操作日志生产者。
NameServer 返回“没有路由”，表示当前没有 Broker 提供该 Topic 的队列信息，客户端无法定位目标。

## 注解注册评分生产者

采购模块的 `SupplierScoreRocketMQTemplate` 继承 `RocketMQTemplate`，通过注解注册：

```java
@ExtRocketMQTemplateConfiguration(
    group = "${supplier-score.rocketmq.producer.group:erp-supplier-score-producer}"
)
public class SupplierScoreRocketMQTemplate extends RocketMQTemplate {
}
```

该注解包含 Spring 组件注册，Starter 会为专用模板创建并启动一个生产者。
`ScoreRecalcPendingService` 按 `SupplierScoreRocketMQTemplate` 具体类型注入，不依赖默认模板或默认生产者组。
NameServer、发送超时、同步及异步重试次数读取公共 `rocketmq` 配置，评分组名读取 `supplier-score.rocketmq.producer.group`。
不需要再配置公共 `rocketmq.producer.group`，否则会额外启用默认生产者。
生产者注册、启动及关闭由 Starter 和模板生命周期负责，不额外手动调用 `start()` 或 `shutdown()`。

评分消费者的 Topic 与消费组也读取 `supplier-score.rocketmq` 配置，避免发送和订阅使用不同 Topic。
将来若需要独立日志组，再新增对应专用 Template；只有消息 Topic 不同时，也可以共用同一个生产者。

## 云端 Topic 初始化

当前云端使用 Docker 容器 `rmqnamesrv` 和 `rmqbroker`。在云服务器执行一次以下命令，显式创建评分 Topic：

```bash
docker exec rmqbroker sh mqadmin updateTopic -n rmqnamesrv:9876 -b 127.0.0.1:10911 -t erp-supplier-score-recalc -r 4 -w 4
docker exec rmqbroker sh mqadmin topicRoute -n rmqnamesrv:9876 -t erp-supplier-score-recalc
```

应用启动不自动修改 Broker 的 Topic 配置。
Producer group 标识发送方，consumer group 标识消费方；两者与 Topic 是不同概念，不必同名。
同一个生产者可以发送多个 Topic，所以不是每加一种消息就必须加一个生产者组。

## 验证

`SupplierScoreRocketMQTemplateTest` 验证真实 Starter 的注解注册、配置继承、具体类型注入和客户端关闭。
`SupplierScoreRocketMQTemplateIT` 使用 `-Dmq.real.it=true` 启用，对注解创建的评分客户端执行只读云端集群和 Topic 路由查询，不发送消息或改动业务数据。
只读路由检查不能代替业务投递消费验证。

`SupplierScoreMqClosedLoopIT` 使用 `-Dscore.mq.e2e.write=true` 显式启用真实闭环：

- 若正式评分 Topic 不存在，创建四读四写队列；存在时不覆盖原配置。
- 使用唯一测试 Topic、消费组和独立正数主键，运行真实评分生产者、五分钟延迟、消费者、事务代理、Mapper、Redis 和日志服务。
- 覆盖冷缓存、热缓存增量、多单并发合并、重复订单增量与重复消息消费，独立核对供应商和供货产品分数。
- 结束后只清理本次测试资源，保留正式 Topic，不回退共享单号序列。

在 `erp-server` 执行，先通过本机环境变量设置 `ERP_IT_JDBC_PASSWORD`，不要将密码写入命令或测试文件：

```powershell
mvn -pl erp-purchase -am '-Dtest=SupplierScoreMqClosedLoopIT' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dscore.mq.e2e.write=true' test
```

该测试会写入隔离测试数据和初始化 Topic，不能作为普通只读探测随意运行。默认连接本机 `13307/erp`、Redis `6379` 和配置中的云端 MQ；可用 `score.it.url`、`score.it.user`、`score.it.redis`、`mq.it.name-server` 指定受控环境。

## 延迟等级与验证边界

RocketMQ 延迟等级从一开始计数。当前真实 Broker 的第八级为 `4m`、第九级为 `5m`，评分使用第九级，对应五分钟合并窗口。闭环测试会读取 Broker 实际配置检查这一对应关系，不修改共享 Broker 的延迟表。

测试提交隔离的已确认入库事实后注册 `AFTER_COMMIT` 发布评分事件，验证的是评分 MQ 链路，不替代完整仓库入库确认接口及库存流水验收。
现有架构仍为事务提交后投递并由每日校正兜底，没有新增 outbox；正常闭环通过不等于跨数据库与 MQ 的绝对不丢消息保证，也不能证明所有生产负载下绝无死锁。
