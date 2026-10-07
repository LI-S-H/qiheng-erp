package com.qiheng.erp.admin;

import com.qiheng.erp.common.constant.SystemExceptionConstants;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.common.mq.SystemExceptionRecordMessage;
import com.qiheng.erp.common.mq.SystemExceptionRocketMQTemplate;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.dashboard.mapper.SystemExceptionMapper;
import com.qiheng.erp.dashboard.mq.SystemExceptionRecordConsumer;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 显式启用后验证系统异常记录的真实闭环：真实云端 MQ 发送、真实消费者入库、真实 MySQL 核对。
 *
 * <p>使用隔离 Topic 与隔离消费组，业务数据仅写入带 runId 标识的 system_exception 行，
 * 断言完成后无论成败都清理测试行与 MQ 临时资源；生产 Topic、生产消费组只读不触碰。</p>
 *
 * <p>运行方式：{@code mvn -pl erp-admin test -Dtest=SystemExceptionRecordClosedLoopIT
 * -Dsys-exc.it.write=true}（Redis 需在本机 6379，MySQL 需在本机 13307）。</p>
 *
 * @author Li
 * @since 2026-10-07
 */
@EnabledIfSystemProperty(named = "sys-exc.it.write", matches = "true")
class SystemExceptionRecordClosedLoopIT {

    private static final String NAME_SERVER = System.getProperty("mq.it.name-server", "8.154.27.144:9876");

    /** 等待真实 MQ 往返与消费入库的总超时（秒） */
    private static final long CONSUME_DEADLINE_SECONDS = 60;

    @Test
    void realMqRoundTripPersistsSystemExceptionAndSkipsDuplicate() throws Exception {
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String topic = "erp-sys-exc-it-" + runId;
        String group = "erp-sys-exc-it-consumer-" + runId;
        String errorMessage = "it-npe-" + runId;
        String probeErrorMessage = "it-probe-" + runId;
        DefaultMQProducer admin = new DefaultMQProducer("erp-sys-exc-it-admin-" + runId);
        admin.setNamesrvAddr(NAME_SERVER);
        admin.setInstanceName(runId);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        // 重建仅允许显式授权的本地开发库，不能用于远端或其他表。
        if (Boolean.getBoolean("sys-exc.it.recreate-table")) {
            recreateLocalExceptionTable(jdbc);
        }
        Throwable primaryFailure = null;
        String brokerAddress = null;
        boolean topicCreationAttempted = false;
        boolean consumerStartupAttempted = false;
        try {
            admin.start();
            var api = admin.getDefaultMQProducerImpl().getMqClientFactory().getMQClientAPIImpl();
            var cluster = api.getBrokerClusterInfo(5000);
            String broker = cluster.getBrokerAddrTable().values().stream()
                    .map(b -> b.getBrokerAddrs().get(0L)).filter(Objects::nonNull).findFirst().orElseThrow();
            brokerAddress = broker;
            topicCreationAttempted = true;
            ensureTopic(admin, broker, topic);

            consumerStartupAttempted = true;
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(RocketMQAutoConfiguration.class))
                    .withUserConfiguration(RealExceptionConfiguration.class)
                    .withPropertyValues("rocketmq.name-server=" + NAME_SERVER,
                            "system-exception.rocketmq.topic=" + topic,
                            "system-exception.rocketmq.producer.group=erp-sys-exc-it-producer-" + runId,
                            "system-exception.rocketmq.consumer.group=" + group)
                    .run(context -> {
                        assertNull(context.getStartupFailure(), "真实上下文必须无装配失败");
                        SystemExceptionMqPublisher publisher = context.getBean(SystemExceptionMqPublisher.class);
                        // Consumer Bean 注册即代表真实消费者已订阅隔离 Topic
                        assertNotNull(context.getBean(SystemExceptionRecordConsumer.class));

                        // 1. HTTP 未知异常场景：模拟 /sales 模块请求上下文，走全局拦截器同款入口
                        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/sales/order");
                        // 来源推断基于 servletPath，需显式设置（构造器只填 requestURI）
                        request.setServletPath("/sales/order");
                        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
                        publisher.publishSystemError(new RuntimeException(errorMessage));
                        RequestContextHolder.resetRequestAttributes();

                        // 2. 定时任务失败场景：同模块直发入口
                        publisher.publishJobFailure("it-job-" + runId, "IT 任务失败 " + runId,
                                "人工重跑", SystemExceptionConstants.SEVERITY_HIGH);

                        // 3. 真实等待 MQ 投递 + 消费入库，共 2 条
                        awaitRows(jdbc, runId, 2);

                        // 4. 逐字段核对 HTTP 异常记录
                        Map<String, Object> sales = jdbc.queryForMap(
                                "SELECT event_id, exception_no, exception_type, source_module, source_no, severity,"
                                        + " error_code, error_message, detail_summary, resolve_hint, status, occurred_at"
                                        + " FROM system_exception WHERE source_module = 'SALES' AND error_message = ?",
                                errorMessage);
                        assertTrue(String.valueOf(sales.get("exception_no")).matches("SE\\d{13}"),
                                "异常编号必须由 BillNoGenerator 生成: " + sales.get("exception_no"));
                        assertEquals("SYSTEM_ERROR", sales.get("exception_type"));
                        assertEquals("SALES", sales.get("source_module"));
                        assertEquals("POST /sales/order", sales.get("source_no"));
                        assertEquals("HIGH", sales.get("severity"));
                        assertEquals("RuntimeException", sales.get("error_code"));
                        assertTrue(String.valueOf(sales.get("event_id")).matches("[1-9][0-9]{0,18}"));
                        assertEquals("SALES模块接口 POST /sales/order 抛出 RuntimeException", sales.get("detail_summary"));
                        assertEquals("查看应用日志 ERROR 级堆栈定位根因", sales.get("resolve_hint"));
                        assertEquals("PENDING", sales.get("status"));
                        assertNotNull(sales.get("occurred_at"), "发生时间必须落库");

                        // 5. 逐字段核对定时任务记录
                        Map<String, Object> job = jdbc.queryForMap(
                                "SELECT exception_type, source_module, error_code, severity, status"
                                        + " FROM system_exception WHERE source_no = ?",
                                "it-job-" + runId);
                        assertEquals("JOB_FAILED", job.get("exception_type"));
                        assertEquals("SCHEDULER", job.get("source_module"));
                        assertEquals("JOB_EXECUTION_FAILED", job.get("error_code"));
                        assertEquals("HIGH", job.get("severity"));
                        assertEquals("PENDING", job.get("status"));

                        // 6. 重投必须沿用原事件标识；探针只能证明链路存活，不能证明跨队列消费顺序。
                        SystemExceptionRecordMessage duplicate = new SystemExceptionRecordMessage();
                        duplicate.setEventId(String.valueOf(sales.get("event_id")));
                        duplicate.setExceptionType(SystemExceptionConstants.TYPE_SYSTEM_ERROR);
                        duplicate.setSourceModule("SALES");
                        duplicate.setSourceNo("POST /sales/order");
                        duplicate.setSeverity(SystemExceptionConstants.SEVERITY_HIGH);
                        duplicate.setErrorCode("RuntimeException");
                        duplicate.setErrorMessage(errorMessage);
                        duplicate.setDetailSummary("SALES模块接口 POST /sales/order 抛出 RuntimeException");
                        duplicate.setOccurredAt(toEpochMillis(sales.get("occurred_at")));
                        publisher.publish(duplicate);

                        // 同一秒、同来源、同错误的另一次真实异常必须单独保存。
                        SystemExceptionRecordMessage distinct = new SystemExceptionRecordMessage();
                        distinct.setEventId(IdWorker.getIdStr());
                        distinct.setExceptionType(duplicate.getExceptionType());
                        distinct.setSourceModule(duplicate.getSourceModule());
                        distinct.setSourceNo(duplicate.getSourceNo());
                        distinct.setSeverity(duplicate.getSeverity());
                        distinct.setErrorCode(duplicate.getErrorCode());
                        distinct.setErrorMessage(duplicate.getErrorMessage());
                        distinct.setDetailSummary(duplicate.getDetailSummary());
                        distinct.setOccurredAt(duplicate.getOccurredAt());
                        publisher.publish(distinct);

                        SystemExceptionRecordMessage probe = new SystemExceptionRecordMessage();
                        probe.setEventId(IdWorker.getIdStr());
                        probe.setExceptionType(SystemExceptionConstants.TYPE_SYSTEM_ERROR);
                        probe.setSourceModule("SALES");
                        probe.setSourceNo("POST /sales/order");
                        probe.setSeverity(SystemExceptionConstants.SEVERITY_HIGH);
                        probe.setErrorCode("99999");
                        probe.setErrorMessage(probeErrorMessage);
                        probe.setDetailSummary("IT 探针 " + runId);
                        probe.setOccurredAt(System.currentTimeMillis());
                        publisher.publish(probe);

                        // 探针入库 = 消费者真实工作完成一轮
                        awaitProbe(jdbc, probeErrorMessage);
                        Thread.sleep(3000);
                        Integer total = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM system_exception WHERE source_no LIKE ? OR error_message LIKE ?",
                                Integer.class, "%" + runId + "%", "%" + runId + "%");
                        awaitRows(jdbc, runId, 4);
                        total = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM system_exception WHERE source_no LIKE ? OR error_message LIKE ?",
                                Integer.class, "%" + runId + "%", "%" + runId + "%");
                        assertEquals(4, total, "重投不新增，不同事件新增，总数应为原有2条加不同事件和探针");
                        Integer dupCount = jdbc.queryForObject(
                                "SELECT COUNT(*) FROM system_exception WHERE source_module='SALES' AND error_message = ?",
                                Integer.class, errorMessage);
                        assertEquals(2, dupCount, "同四字段但不同事件必须保留两条");
                        assertEquals(1, jdbc.queryForObject(
                                "SELECT COUNT(*) FROM system_exception WHERE event_id = ?", Integer.class,
                                duplicate.getEventId()), "原事件重投只能保留一条");

                        // 并发真实消费者共享一个事件标识，使用真实Mapper及数据库唯一索引竞争。
                        SystemExceptionRecordMessage concurrent = context.getBean(
                                com.fasterxml.jackson.databind.ObjectMapper.class)
                                .convertValue(distinct, SystemExceptionRecordMessage.class);
                        concurrent.setEventId(IdWorker.getIdStr());
                        var executor = Executors.newFixedThreadPool(8);
                        try {
                            CountDownLatch gate = new CountDownLatch(1);
                            List<Future<?>> futures = new java.util.ArrayList<>();
                            for (int i = 0; i < 8; i++) {
                                futures.add(executor.submit(() -> {
                                    gate.await();
                                    context.getBean(SystemExceptionRecordConsumer.class).onMessage(concurrent);
                                    return null;
                                }));
                            }
                            gate.countDown();
                            for (Future<?> future : futures) {
                                future.get(30, TimeUnit.SECONDS);
                            }
                            assertEquals(1, jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM system_exception WHERE event_id = ?", Integer.class,
                                    concurrent.getEventId()), "首次并发消费同一事件只能写入一次且不应死锁");
                        } finally {
                            executor.shutdownNow();
                        }
                        System.out.println("SYS_EXC_E2E_OK runId=" + runId);
                    });
        } catch (Exception | AssertionError failure) {
            // 资源清理失败只能作为补充信息，不能覆盖真正的链路失败原因。
            primaryFailure = failure;
            throw failure;
        } finally {
            List<Exception> cleanupFailures = new java.util.ArrayList<>();
            if (consumerStartupAttempted) {
                try {
                    jdbc.update("DELETE FROM system_exception WHERE source_no LIKE ? OR error_message LIKE ?",
                            "%" + runId + "%", "%" + runId + "%");
                } catch (Exception failure) {
                    cleanupFailures.add(failure);
                }
            }
            try {
                // 复用已解析的Broker，不再次访问可能已断开的NameServer获取集群信息。
                if (brokerAddress != null && topicCreationAttempted) {
                    var api = admin.getDefaultMQProducerImpl().getMqClientFactory().getMQClientAPIImpl();
                    if (consumerStartupAttempted) {
                        try {
                            deleteTestSubscriptionGroup(admin, brokerAddress, group);
                        } catch (Exception failure) {
                            cleanupFailures.add(failure);
                        }
                    }
                    for (String owned : List.of(topic, "%RETRY%" + group, "%DLQ%" + group)) {
                        try {
                            api.deleteTopicInBroker(brokerAddress, owned, 5000);
                        } catch (Exception failure) {
                            cleanupFailures.add(failure);
                        }
                        try {
                            api.deleteTopicInNameServer(NAME_SERVER, owned, 5000);
                        } catch (Exception failure) {
                            cleanupFailures.add(failure);
                        }
                    }
                }
            } catch (Exception failure) {
                cleanupFailures.add(failure);
            } finally {
                try {
                    admin.shutdown();
                } catch (Exception failure) {
                    cleanupFailures.add(failure);
                }
            }
            if (!cleanupFailures.isEmpty()) {
                IllegalStateException failure = new IllegalStateException("测试资源清理未全部成功，按 runId 核查：" + runId);
                cleanupFailures.forEach(failure::addSuppressed);
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(failure);
                } else {
                    throw failure;
                }
            } else if (topicCreationAttempted) {
                System.out.println("SYS_EXC_E2E_CLEANED topic=" + topic + " group=" + group);
            } else {
                System.out.println("SYS_EXC_E2E_NO_MQ_RESOURCES_CREATED runId=" + runId);
            }
        }
    }

    /** 轮询等待带 runId 标识的记录数达到期望值，超时判定真实链路失败。 */
    private void awaitRows(JdbcTemplate jdbc, String runId, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONSUME_DEADLINE_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM system_exception WHERE source_no LIKE ? OR error_message LIKE ?",
                    Integer.class, "%" + runId + "%", "%" + runId + "%");
            if (count != null && count >= expected) {
                return;
            }
            Thread.sleep(1000);
        }
        fail("真实消费未在期限内完成入库 expected=" + expected);
    }

    /** 轮询等待探针消息入库，证明消费者在本轮仍正常消费。 */
    private void awaitProbe(JdbcTemplate jdbc, String probeErrorMessage) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CONSUME_DEADLINE_SECONDS);
        while (System.nanoTime() < deadline) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM system_exception WHERE error_message = ?",
                    Integer.class, probeErrorMessage);
            if (count != null && count >= 1) {
                return;
            }
            Thread.sleep(1000);
        }
        fail("探针消息未被消费，幂等结论不可信");
    }

    /** DATETIME 查询结果按业务时区还原，用于验证同一秒的不同事件不会合并。 */
    private long toEpochMillis(Object occurredAt) {
        LocalDateTime time = occurredAt instanceof java.sql.Timestamp timestamp
                ? timestamp.toLocalDateTime()
                : (LocalDateTime) occurredAt;
        return time.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    }

    /** 显式授权时只重建本机13307的异常表，不执行种子数据或其他表DDL。 */
    private void recreateLocalExceptionTable(JdbcTemplate jdbc) throws Exception {
        String url = ((DriverManagerDataSource) jdbc.getDataSource()).getUrl();
        if (url == null || !url.matches("jdbc:mysql://(?:localhost|127\\.0\\.0\\.1):13307/erp(?:\\?.*)?")) {
            throw new IllegalStateException("拒绝重建非本机13307开发库的异常表");
        }
        Path sqlFile = Path.of("../../docs/database/sql/007_mvp_system_exception.sql").toAbsolutePath().normalize();
        String sql = Files.readString(sqlFile, StandardCharsets.UTF_8);
        int start = sql.indexOf("CREATE TABLE IF NOT EXISTS system_exception (");
        int end = sql.indexOf(";", start);
        if (start < 0 || end < 0 || !sql.substring(start, end).contains("uk_system_exception_event_id")) {
            throw new IllegalStateException("异常表建表SQL缺失或没有事件唯一索引");
        }
        System.out.println("SYS_EXC_RECREATE_LOCAL_TABLE existingRows=" + jdbc.queryForObject(
                "SELECT COUNT(*) FROM system_exception", Long.class));
        jdbc.execute("DROP TABLE system_exception");
        jdbc.execute(sql.substring(start, end));
    }

    /** 仅为不存在的隔离 Topic 创建队列，并等待真实 NameServer 注册路由。 */
    private void ensureTopic(DefaultMQProducer producer, String broker, String topic) throws Exception {
        var api = producer.getDefaultMQProducerImpl().getMqClientFactory().getMQClientAPIImpl();
        try {
            api.getTopicRouteInfoFromNameServer(topic, 5000);
            return;
        } catch (MQClientException e) {
            if (e.getResponseCode() != 17) throw e;
        }
        api.createTopic(broker, "TBW102", new TopicConfig(topic, 4, 4, 6), 5000);
        for (int i = 0; i < 20; i++) {
            try {
                api.getTopicRouteInfoFromNameServer(topic, 5000);
                System.out.println("SYS_EXC_E2E_TOPIC_CREATED topic=" + topic);
                return;
            } catch (MQClientException e) {
                if (e.getResponseCode() != 17) throw e;
                Thread.sleep(3000);
            }
        }
        fail("Topic 创建后未注册路由: " + topic);
    }

    /** 兼容 4.9 Broker 的 removeOffset 字段删除隔离消费组，组名不匹配直接拒绝。 */
    private void deleteTestSubscriptionGroup(DefaultMQProducer producer, String broker, String group) throws Exception {
        if (!group.matches("erp-sys-exc-it-consumer-[a-zA-Z0-9]{16}")) {
            throw new IllegalArgumentException("拒绝清理非隔离测试组：" + group);
        }
        var header = new org.apache.rocketmq.remoting.protocol.header.DeleteSubscriptionGroupRequestHeader();
        header.setGroupName(group);
        header.setCleanOffset(true);
        var request = org.apache.rocketmq.remoting.protocol.RemotingCommand.createRequestCommand(
                org.apache.rocketmq.remoting.protocol.RequestCode.DELETE_SUBSCRIPTIONGROUP, header);
        request.addExtField("removeOffset", "true");
        var api = producer.getDefaultMQProducerImpl().getMqClientFactory().getMQClientAPIImpl();
        api.getRemotingClient().invokeSync(broker, request, 5000);
    }

    /** 密码优先读取本机环境变量，缺失时安全读取开发配置，不在输出中打印配置内容。 */
    private static DataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(System.getProperty("sys-exc.it.url",
                "jdbc:mysql://localhost:13307/erp?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        source.setUsername(System.getProperty("sys-exc.it.user", "root"));
        String password = System.getenv("ERP_IT_JDBC_PASSWORD");
        if (password == null || password.isBlank()) {
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource("src/main/resources/application-dev.yml"));
            password = Objects.requireNonNull(yaml.getObject()).getProperty("spring.datasource.password");
        }
        source.setPassword(Objects.requireNonNull(password, "请设置 ERP_IT_JDBC_PASSWORD"));
        return source;
    }

    /** 最小真实装配：仅系统异常链路的真实 Producer、Consumer、Mapper 与编号生成器。 */
    @Configuration(proxyBeanMethods = false)
    @Import({SystemExceptionRocketMQTemplate.class, SystemExceptionMqPublisher.class,
            SystemExceptionRecordConsumer.class})
    static class RealExceptionConfiguration {

        @Bean
        DataSource source() {
            return dataSource();
        }

        @Bean
        com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper();
        }

        @Bean(destroyMethod = "shutdown")
        RedissonClient redissonClient() {
            Config config = new Config();
            config.useSingleServer().setAddress(System.getProperty("sys-exc.it.redis", "redis://localhost:6379"));
            return Redisson.create(config);
        }

        @Bean
        StringRedisTemplate redis(RedissonClient client) {
            return new StringRedisTemplate(new RedissonConnectionFactory(client));
        }

        @Bean
        BillNoGenerator billNoGenerator(StringRedisTemplate redis, RedissonClient client) {
            return new BillNoGenerator(redis, client);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLogImpl(NoLoggingImpl.class);
            configuration.addMapper(SystemExceptionMapper.class);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source);
            factory.setConfiguration(configuration);
            return factory.getObject();
        }

        @Bean
        SqlSessionTemplate session(SqlSessionFactory factory) {
            return new SqlSessionTemplate(factory);
        }

        @Bean
        SystemExceptionMapper systemExceptionMapper(SqlSessionTemplate template) {
            return template.getMapper(SystemExceptionMapper.class);
        }
    }
}
