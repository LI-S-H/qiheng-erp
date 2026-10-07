package com.qiheng.erp.purchase.mq;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreEventTrigger;
import com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreFireMessage;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import com.qiheng.erp.purchase.domain.supplierscore.constant.SupplierScoreConstants;
import com.qiheng.erp.purchase.mapper.*;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import com.qiheng.erp.warehouse.mapper.InboundBillItemMapper;
import com.qiheng.erp.purchase.service.impl.SupplierScoreChangeLogServiceImpl;
import com.qiheng.erp.purchase.service.impl.SupplierScoreRecalculateServiceImpl;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import com.qiheng.erp.purchase.service.scoring.*;
import com.qiheng.erp.purchase.service.support.PendingRedisSupport;
import com.qiheng.erp.purchase.service.support.ScoreRecalcPendingService;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.TopicConfig;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.protocol.subscription.SubscriptionGroupConfig;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 显式启用后验证真实云端 MQ、MySQL、Redis 评分闭环。
 * 正式 Topic 仅只读核对；业务测试使用唯一 Topic、消费组和独立记录。
 * 本测试提交测试入库事实后注册 AFTER_COMMIT，不代替完整仓库入库确认接口验收。
 * 仅显式设置 score.mq.e2e.keep-data=true 且全部校验成功时保留隔离业务数据供人工审阅。
 * MQ 临时 Topic、消费组和客户端始终回收，默认或失败场景仍清理业务记录与缓存。
 */
@EnabledIfSystemProperty(named = "score.mq.e2e.write", matches = "true")
class SupplierScoreMqClosedLoopIT {
    private static final String NAME_SERVER = System.getProperty("mq.it.name-server", "8.154.27.144:9876");

    @Test
    void realDelayedDeliveryShouldPersistExactScoresAndHandleDuplicateAndMergedEvents() throws Exception {
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String topic = "erp-score-it-" + runId;
        String group = "erp-score-it-consumer-" + runId;
        DefaultMQProducer admin = new DefaultMQProducer("erp-score-it-admin-" + runId);
        admin.setNamesrvAddr(NAME_SERVER);
        admin.setInstanceName(runId);
        SupplierScoreMqFixture fixture = null;
        String broker = null;
        boolean testTopicOwned = false;
        boolean testGroupOwned = false;
        boolean keepDataRequested = Boolean.getBoolean("score.mq.e2e.keep-data");
        boolean allChecksPassed = false;
        AtomicBoolean assertionsPassed = new AtomicBoolean(false);
        Throwable primaryFailure = null;
        try {
            admin.start();
            var api = admin.getDefaultMQProducerImpl().getMqClientFactory().getMQClientAPIImpl();
            var cluster = api.getBrokerClusterInfo(5000);
            broker = cluster.getBrokerAddrTable().values().stream()
                    .map(b -> b.getBrokerAddrs().get(0L)).filter(Objects::nonNull).findFirst().orElseThrow();
            assertFalse(api.getTopicRouteInfoFromNameServer("erp-supplier-score-recalc", 5000).getBrokerDatas().isEmpty());
            // 正式组仅只读检查位点，不重置、不消费、不清理；延迟 Topic 的未来消息不等同于当前消费积压。
            String productionGroup = "erp-supplier-score-fire-consumer";
            boolean productionGroupPresent = api.getAllSubscriptionGroup(broker, 5000)
                    .getSubscriptionGroupTable().containsKey(productionGroup);
            System.out.println("MQ_PRODUCTION_READONLY groupPresent=" + productionGroupPresent);
            if (productionGroupPresent) {
                try {
                    var offsets = api.getConsumeStats(broker, productionGroup, "erp-supplier-score-recalc", 5000);
                    System.out.printf("MQ_PRODUCTION_READONLY offsetQueues=%d currentLag=%d%n",
                            offsets.getOffsetTable().size(), offsets.computeTotalDiff());
                } catch (Exception unavailable) {
                    System.out.println("MQ_PRODUCTION_READONLY offsetsUnavailable=" + unavailable.getClass().getSimpleName());
                }
            }
            // 验证五分钟延迟等级，避免服务器自定义配置导致测试误判。
            String[] delays = api.getBrokerConfig(broker, 5000).getProperty("messageDelayLevel").split(" ");
            assertEquals("5m", delays[SupplierScoreConstants.MQ_DELAY_LEVEL_5MIN - 1], "评分延迟等级必须对应五分钟");
            // 唯一运行标识归本测试所有，创建或路由查询失败也必须尝试回收。
            testTopicOwned = true;
            ensureTopic(admin, broker, topic);
            SubscriptionGroupConfig subscription = new SubscriptionGroupConfig();
            subscription.setGroupName(group);
            testGroupOwned = true;
            api.createSubscriptionGroup(broker, subscription, 5000);

            var dataSource = dataSource();
            fixture = new SupplierScoreMqFixture(new JdbcTemplate(dataSource), runId);
            fixture.create();
            SupplierScoreMqFixture fixtures = fixture;
            new ApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(RocketMQAutoConfiguration.class))
                    .withUserConfiguration(RealScoreConfiguration.class)
                    .withPropertyValues("rocketmq.name-server=" + NAME_SERVER,
                            "supplier-score.rocketmq.topic=" + topic,
                            "supplier-score.rocketmq.producer.group=erp-score-it-producer-" + runId,
                            "supplier-score.rocketmq.consumer.group=" + group,
                            "rocketmq.producer.retry-times-when-send-failed=2")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(
                                context.getBean(SupplierScoreRecalculateService.class)), "必须经过真实事务代理");
                        JdbcTemplate jdbc = new JdbcTemplate(context.getBean(DataSource.class));
                        StringRedisTemplate redis = context.getBean(StringRedisTemplate.class);
                        SupplierQualityAmountCacheService cache = context.getBean(SupplierQualityAmountCacheService.class);
                        ScoreRecalcPendingService pending = context.getBean(ScoreRecalcPendingService.class);
                        ObjectMapper json = context.getBean(ObjectMapper.class);
                        var producer = context.getBean(SupplierScoreRocketMQTemplate.class).getProducer();
                        // 捕获 Template 实际转换出的原生消息，验证 Header、Tag、延迟等级和 JSON 字节体完整传递。
                        List<Message> nativeMessages = new CopyOnWriteArrayList<>();
                        producer.getDefaultMQProducerImpl().registerSendMessageHook(new org.apache.rocketmq.client.hook.SendMessageHook() {
                            @Override public String hookName() { return "score-batch-verification"; }
                            @Override public void sendMessageBefore(org.apache.rocketmq.client.hook.SendMessageContext hook) {
                                if (topic.equals(hook.getMessage().getTopic())) nativeMessages.add(hook.getMessage());
                            }
                            @Override public void sendMessageAfter(org.apache.rocketmq.client.hook.SendMessageContext hook) { }
                        });
                        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
                        Map<Long, String> tokens = new HashMap<>();
                        try {
                            for (var sample : fixtures.samples()) {
                                assertFalse(Boolean.TRUE.equals(redis.hasKey(SupplierScoreRedisKeys.pendingKey(sample.supplierId()))));
                                assertNull(cache.read(sample.supplierId(), today));
                                if (!sample.label().equals("cold")) {
                                    // 暖单与暖多单均使用增量路径，沿用供应商已有交付分。
                                    cache.overwrite(sample.supplierId(), today,
                                            new SupplierQualityAmountCacheService.Amounts(BigInteger.ZERO, BigInteger.ZERO));
                                    jdbc.update("UPDATE supplier SET delivery_score=7500 WHERE id=?", sample.supplierId());
                                }
                                var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
                                transaction.executeWithoutResult(status ->
                                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                                            @Override
                                            public void afterCommit() {
                                                tokens.put(sample.supplierId(), pending.mergeAndScheduleFire(trigger(jdbc, sample, 0)));
                                            }
                                        }));
                                if (sample.label().equals("merged")) {
                                    // 并发合并同一供应商的第二单与首单重复事件，不重复投递新窗口。
                                    ExecutorService workers = Executors.newFixedThreadPool(2);
                                    try {
                                        var a = workers.submit(() -> pending.mergeAndScheduleFire(trigger(jdbc, sample, 1)));
                                        var b = workers.submit(() -> pending.mergeAndScheduleFire(trigger(jdbc, sample, 0)));
                                        assertEquals(tokens.get(sample.supplierId()), a.get(15, TimeUnit.SECONDS));
                                        assertEquals(tokens.get(sample.supplierId()), b.get(15, TimeUnit.SECONDS));
                                    } finally {
                                        workers.shutdownNow();
                                    }
                                }
                                String pendingJson = redis.opsForValue().get(SupplierScoreRedisKeys.pendingKey(sample.supplierId()));
                                var snapshot = json.readValue(pendingJson, ScoreRecalcPendingService.PendingSnapshot.class);
                                assertTrue(tokens.get(sample.supplierId()).matches("SC\\d{13}"), "真实生成器必须产生统一业务批次号");
                                assertEquals(tokens.get(sample.supplierId()), snapshot.getBatchNo());
                                assertEquals(snapshot.getBatchNo(), json.readTree(pendingJson).get("batchNo").asText());
                                assertFalse(json.readTree(pendingJson).has("batchToken"));
                                List<Message> actualMessages = nativeMessages.stream()
                                        .filter(m -> tokens.get(sample.supplierId()).equals(m.getKeys())).toList();
                                assertEquals(1, actualMessages.size(), "每个合并窗口仅发一条首次消息");
                                Message actual = actualMessages.getFirst();
                                assertEquals("SCHEDULED_FIRE", actual.getTags());
                                assertEquals(9, actual.getDelayTimeLevel());
                                var actualBody = json.readTree(actual.getBody());
                                assertEquals(snapshot.getBatchNo(), actualBody.get("batchNo").asText());
                                assertEquals(sample.supplierId(), actualBody.get("supplierId").asLong());
                                assertFalse(actualBody.has("batchToken"));
                                assertEquals(sample.label().equals("merged") ? 3 : 1, snapshot.getMergedCount());
                                // 实际 Redis 中使用枚举的原字符串值，所有完成采购单和日志单号必须完整保留。
                                assertEquals(new HashSet<>(sample.orderIds()), new HashSet<>(snapshot.getSourceRefs().stream()
                                        .map(ScoreRecalcPendingService.PendingSnapshot.SourceRef::getSourceRefId).toList()));
                                for (var ref : snapshot.getSourceRefs()) {
                                    assertEquals(TriggerType.INBOUND_TRIGGER, ref.getTriggerType());
                                    assertEquals(jdbc.queryForObject("SELECT purchase_no FROM purchase_order WHERE id=?", String.class,
                                            ref.getSourceRefId()), ref.getSourceRefNo());
                                }
                                for (var ref : json.readTree(pendingJson).get("sourceRefs")) {
                                    assertEquals("INBOUND_TRIGGER", ref.get("triggerType").asText());
                                    assertFalse(ref.has("sourceRefType"));
                                }
                                assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?",
                                        Integer.class, sample.supplierId()));
                                System.out.printf("MQ_E2E_SENT case=%s supplier=%d batchNo=%s delay=5m%n",
                                        sample.label(), sample.supplierId(), tokens.get(sample.supplierId()));
                            }
                            assertEquals(fixtures.samples().size(), new HashSet<>(tokens.values()).size(), "不同供应商窗口不得复用编号");
                            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(390);
                            while (fixtures.samples().stream().anyMatch(s -> Boolean.TRUE.equals(
                                    redis.hasKey(SupplierScoreRedisKeys.pendingKey(s.supplierId()))))) {
                                assertTrue(System.nanoTime() < deadline, "真实延迟消费未在期限内完成");
                                Thread.sleep(15000);
                                System.out.println("MQ_E2E_WAIT pending=" + fixtures.samples().stream().filter(s ->
                                        Boolean.TRUE.equals(redis.hasKey(SupplierScoreRedisKeys.pendingKey(s.supplierId())))).count());
                            }
                            for (var sample : fixtures.samples()) {
                                verifyScores(jdbc, sample);
                                int factor = sample.label().equals("merged") ? 2 : 1;
                                var totals = cache.read(sample.supplierId(), today);
                                assertNotNull(totals);
                                assertEquals(BigInteger.valueOf(18000000L * factor), totals.qualifiedRaw());
                                assertEquals(BigInteger.valueOf(12000000L * factor), totals.defectiveRaw());
                                for (Long orderId : sample.orderIds()) assertTrue(Boolean.TRUE.equals(redis.opsForHash().hasKey(
                                        SupplierScoreRedisKeys.qualityAmountKey(sample.supplierId()), "applied:" + orderId)), "合并批次必须逐单去重登记");
                                Long ttl = redis.getExpire(SupplierScoreRedisKeys.qualityAmountKey(sample.supplierId()), TimeUnit.SECONDS);
                                assertNotNull(ttl); assertTrue(ttl > 85500 && ttl <= 86400);
                                var facts = context.getBean(ScoreFactsQueryService.class);
                                assertEquals(totals, facts.applyCompletedOrder(sample.orderIds().getFirst(), today), "同单重复不得追加金额");
                                int logs = jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?", Integer.class, sample.supplierId());
                                assertEquals(sample.label().equals("cold") ? 7 : 6, logs,
                                        "冷启动记录供应商三个指标；暖增量交付分不变，只记录质量、价格与两个产品各两个指标");
                                assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT batch_no) FROM supplier_score_change_log WHERE supplier_id=? AND batch_no=?",
                                        Integer.class, sample.supplierId(), tokens.get(sample.supplierId())));
                                assertEquals(1, jdbc.queryForObject("SELECT COUNT(DISTINCT batch_no) FROM supplier_score_change_log WHERE supplier_id=?",
                                        Integer.class, sample.supplierId()), "同次重算全部日志必须共用一个批次");
                                assertEquals(logs, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=? AND batch_no=?",
                                        Integer.class, sample.supplierId(), tokens.get(sample.supplierId())), "不能只有部分日志沿用消息编号");
                                for (String sourcesJson : jdbc.queryForList("SELECT related_sources FROM supplier_score_change_log WHERE supplier_id=?",
                                        String.class, sample.supplierId())) {
                                    var sources = json.readTree(sourcesJson);
                                    assertTrue(sources.isArray(), "数据库 JSON 不得双重编码");
                                    assertEquals(sample.orderIds().size(), sources.size(), "所有完成采购单必须完整落库");
                                    var loggedIds = new HashSet<Long>();
                                    for (var source : sources) {
                                        assertEquals("PURCHASE_ORDER", source.get("businessType").asText());
                                        assertTrue(source.get("businessId").isTextual(), "ID 必须为字符串");
                                        Long orderId = Long.valueOf(source.get("businessId").asText());
                                        loggedIds.add(orderId);
                                        assertEquals(jdbc.queryForObject("SELECT purchase_no FROM purchase_order WHERE id=?", String.class, orderId),
                                                source.get("businessNo").asText(), "来源 ID 与采购单号必须配对");
                                    }
                                    assertEquals(new HashSet<>(sample.orderIds()), loggedIds);
                                    System.out.printf("MQ_E2E_LOG_SOURCES case=%s supplier=%d batchNo=%s sources=%s%n",
                                            sample.label(), sample.supplierId(), tokens.get(sample.supplierId()), sourcesJson);
                                }
                                long version = jdbc.queryForObject("SELECT version FROM supplier WHERE id=?", Long.class, sample.supplierId());
                                SupplierScoreFireMessage duplicate = new SupplierScoreFireMessage();
                                duplicate.setMsgType("SCHEDULED_FIRE"); duplicate.setSupplierId(sample.supplierId());
                                duplicate.setBatchNo(tokens.get(sample.supplierId())); duplicate.setScheduledFireAt(System.currentTimeMillis());
                                var sent = producer.send(new Message(topic, "SCHEDULED_FIRE", tokens.get(sample.supplierId()), json.writeValueAsBytes(duplicate)));
                                assertEquals(org.apache.rocketmq.client.producer.SendStatus.SEND_OK, sent.getSendStatus());
                                awaitConsumption(producer, group, sent);
                                assertEquals(logs, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?", Integer.class, sample.supplierId()));
                                assertEquals(version, jdbc.queryForObject("SELECT version FROM supplier WHERE id=?", Long.class, sample.supplierId()));
                                System.out.printf("MQ_E2E_OK case=%s supplier=%d quality=6000 delivery=7500 price=8889 overall=7517 logs=%d qRaw=%s dRaw=%s ttl=%d%n",
                                        sample.label(), sample.supplierId(), logs, totals.qualifiedRaw(), totals.defectiveRaw(), ttl);
                            }
                            assertionsPassed.set(true);
                        } finally {
                            // 即使断言失败，也先停止消费者，避免清理 Redis 时仍有重算线程在运行。
                            for (var container : context.getBeansOfType(DefaultRocketMQListenerContainer.class).values()) {
                                container.getConsumer().setAwaitTerminationMillisWhenShutdown(30000L);
                                container.stop();
                            }
                            for (var sample : fixtures.samples()) {
                                var lock = context.getBean(RedissonClient.class).getLock(SupplierScoreRedisKeys.lockKey(sample.supplierId()));
                                boolean acquired = lock.tryLock(35, TimeUnit.SECONDS);
                                assertTrue(acquired, "清理前仍有在途评分，禁止并发删除缓存");
                                try {
                                    if (!keepDataRequested || !assertionsPassed.get()) {
                                        redis.delete(List.of(SupplierScoreRedisKeys.pendingKey(sample.supplierId()),
                                                SupplierScoreRedisKeys.qualityAmountKey(sample.supplierId())));
                                    }
                                } finally {
                                    if (lock.isHeldByCurrentThread()) lock.unlock();
                                }
                                assertFalse(Boolean.TRUE.equals(redis.hasKey(SupplierScoreRedisKeys.pendingKey(sample.supplierId()))));
                                assertEquals(keepDataRequested && assertionsPassed.get(),
                                        Boolean.TRUE.equals(redis.hasKey(SupplierScoreRedisKeys.qualityAmountKey(sample.supplierId()))));
                            }
                        }
                    });
            allChecksPassed = true;
        } catch (Exception | AssertionError failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            List<Exception> cleanupFailures = new ArrayList<>();
            try {
                if (broker != null) {
                    var api = admin.getDefaultMQProducerImpl().getMqClientFactory().getMQClientAPIImpl();
                    if (testGroupOwned) {
                        try { deleteTestSubscriptionGroup(admin, broker, group); }
                        catch (Exception failure) { cleanupFailures.add(failure); }
                    }
                    if (testTopicOwned) {
                        // Broker 自动创建的重试/死信 Topic 也只按当前独立组名精准清理。
                        for (String owned : List.of(topic, "%RETRY%" + group, "%DLQ%" + group)) {
                            try { api.deleteTopicInBroker(broker, owned, 5000); }
                            catch (Exception failure) { cleanupFailures.add(failure); }
                            try { api.deleteTopicInNameServer(NAME_SERVER, owned, 5000); }
                            catch (Exception failure) { cleanupFailures.add(failure); }
                        }
                    }
                }
                // 临时 MQ 资源回收也属于成功条件；任何失败都不能保留一半完成的验收数据。
                if (fixture != null) {
                    boolean retainData = keepDataRequested && allChecksPassed && primaryFailure == null && cleanupFailures.isEmpty();
                    if (retainData) {
                        try {
                            var jdbc = new JdbcTemplate(dataSource());
                            for (var sample : fixture.samples()) {
                                var supplier = jdbc.queryForMap("SELECT supplier_code,supplier_name FROM supplier WHERE id=?", sample.supplierId());
                                int logs = jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?", Integer.class, sample.supplierId());
                                var batches = jdbc.queryForList("SELECT DISTINCT batch_no FROM supplier_score_change_log WHERE supplier_id=?", String.class, sample.supplierId());
                                System.out.printf("MQ_E2E_RETAINED case=%s supplierId=%d supplierCode=%s supplierName=%s batchNos=%s logs=%d%n",
                                        sample.label(), sample.supplierId(), supplier.get("supplier_code"), supplier.get("supplier_name"), batches, logs);
                                for (Long orderId : sample.orderIds()) {
                                    System.out.printf("MQ_E2E_RETAINED_ORDER supplierId=%d purchaseOrderId=%d purchaseNo=%s%n",
                                            sample.supplierId(), orderId, jdbc.queryForObject("SELECT purchase_no FROM purchase_order WHERE id=?", String.class, orderId));
                                }
                            }
                            System.out.println("MQ_E2E_BUSINESS_DATA_RETAINED runId=" + runId + " qualityCacheRetained=true pendingRetained=false");
                        } catch (Exception failure) {
                            cleanupFailures.add(failure);
                            retainData = false;
                        }
                    }
                    if (!retainData) {
                        try { fixture.cleanup(); } catch (Exception failure) { cleanupFailures.add(failure); }
                        if (keepDataRequested && assertionsPassed.get()) {
                            // 消费上下文已关闭，只有成功候选曾跳过缓存清理；用独立客户端精准回收这些测试键。
                            Config config = new Config();
                            config.useSingleServer().setAddress(System.getProperty("score.it.redis", "redis://localhost:6379"));
                            RedissonClient cleanupClient = Redisson.create(config);
                            try {
                                var redis = new StringRedisTemplate(new RedissonConnectionFactory(cleanupClient));
                                for (var sample : fixture.samples()) redis.delete(List.of(
                                        SupplierScoreRedisKeys.pendingKey(sample.supplierId()),
                                        SupplierScoreRedisKeys.qualityAmountKey(sample.supplierId())));
                            } catch (Exception failure) { cleanupFailures.add(failure); }
                            finally { cleanupClient.shutdown(); }
                        }
                    }
                }
            } finally {
                admin.shutdown();
            }
            if (!cleanupFailures.isEmpty()) {
                IllegalStateException failure = new IllegalStateException("测试资源清理未全部成功，需按本次标识核查：" + runId);
                cleanupFailures.forEach(failure::addSuppressed);
                if (primaryFailure != null) primaryFailure.addSuppressed(failure); else throw failure;
            } else {
                System.out.println("MQ_E2E_CLEANED topic=" + topic + " group=" + group + " productionTopicRetained=true");
            }
        }
    }

    /** 兼容 4.9 Broker 的 removeOffset 字段，避免新版 cleanOffset 未被识别而遗留消费位点。 */
    static void deleteTestSubscriptionGroup(DefaultMQProducer producer, String broker, String group) throws Exception {
        if (!group.matches("erp-score-it-consumer-[a-zA-Z0-9]{16}")) {
            throw new IllegalArgumentException("拒绝清理非隔离测试组：" + group);
        }
        var header = new org.apache.rocketmq.remoting.protocol.header.DeleteSubscriptionGroupRequestHeader();
        header.setGroupName(group); header.setCleanOffset(true);
        var request = org.apache.rocketmq.remoting.protocol.RemotingCommand.createRequestCommand(
                org.apache.rocketmq.remoting.protocol.RequestCode.DELETE_SUBSCRIPTIONGROUP, header);
        // 5.x 客户端字段名为 cleanOffset；当前 4.9.6 Broker 读取 removeOffset，需要同时发送。
        request.addExtField("removeOffset", "true");
        var api = producer.getDefaultMQProducerImpl().getMqClientFactory().getMQClientAPIImpl();
        var response = api.getRemotingClient().invokeSync(broker, request, 5000);
        if (response.getCode() != 0) {
            throw new IllegalStateException("测试组删除失败：" + response.getRemark());
        }
        if (api.getAllSubscriptionGroup(broker, 5000).getSubscriptionGroupTable().containsKey(group)) {
            throw new IllegalStateException("测试消费组未回收：" + group);
        }
    }

    /** 等待 Broker 已提交的消费位点越过重复消息，不能用固定等待后数据没变冒充幂等通过。 */
    private void awaitConsumption(DefaultMQProducer producer, String group,
                                  org.apache.rocketmq.client.producer.SendResult sent) throws Exception {
        var client = producer.getDefaultMQProducerImpl().getMqClientFactory();
        String broker = client.findBrokerAddressInPublish(sent.getMessageQueue().getBrokerName());
        assertNotNull(broker);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < deadline) {
            var stats = client.getMQClientAPIImpl().getConsumeStats(broker, group,
                    sent.getMessageQueue().getTopic(), 5000);
            var offset = stats.getOffsetTable().get(sent.getMessageQueue());
            if (offset != null && offset.getConsumerOffset() > sent.getQueueOffset()) {
                System.out.printf("MQ_E2E_DUPLICATE_CONSUMED queue=%s sentOffset=%d consumerOffset=%d%n",
                        sent.getMessageQueue(), sent.getQueueOffset(), offset.getConsumerOffset());
                return;
            }
            Thread.sleep(1000);
        }
        fail("重复消息未被真实消费：" + sent);
    }

    /** 仅为不存在的 Topic 创建四读四写队列，并等待真实 NameServer 注册路由。 */
    private void ensureTopic(DefaultMQProducer producer, String broker, String topic) throws Exception {
        var api = producer.getDefaultMQProducerImpl().getMqClientFactory().getMQClientAPIImpl();
        try {
            assertFalse(api.getTopicRouteInfoFromNameServer(topic, 5000).getBrokerDatas().isEmpty());
            System.out.println("MQ_TOPIC_EXISTS topic=" + topic);
            return;
        } catch (MQClientException e) {
            if (e.getResponseCode() != 17) throw e;
        }
        api.createTopic(broker, "TBW102", new TopicConfig(topic, 4, 4, 6), 5000);
        for (int i = 0; i < 20; i++) {
            try {
                assertFalse(api.getTopicRouteInfoFromNameServer(topic, 5000).getBrokerDatas().isEmpty());
                System.out.println("MQ_TOPIC_CREATED topic=" + topic + " broker=" + broker + " queues=4");
                return;
            } catch (MQClientException e) {
                if (e.getResponseCode() != 17) throw e;
                Thread.sleep(3000);
            }
        }
        fail("Topic 创建后未注册路由: " + topic);
    }

    /** 为真实 pending 服务构造首次完全入库的采购单来源。 */
    private SupplierScoreEventTrigger trigger(JdbcTemplate jdbc, SupplierScoreMqFixture.Sample sample, int index) {
        SupplierScoreEventTrigger trigger = new SupplierScoreEventTrigger();
        trigger.setSupplierId(sample.supplierId()); trigger.setTriggerType(TriggerType.INBOUND_TRIGGER);
        trigger.setSourceRefId(sample.orderIds().get(index));
        trigger.setSourceRefNo(jdbc.queryForObject("SELECT purchase_no FROM purchase_order WHERE id=?", String.class,
                sample.orderIds().get(index))); trigger.setOccurredAt(System.currentTimeMillis());
        return trigger;
    }

    /** 独立常量期望核对数据库最终分数，不复用被测计算器作为判定依据。 */
    private void verifyScores(JdbcTemplate jdbc, SupplierScoreMqFixture.Sample sample) {
        Map<String, Object> supplier = jdbc.queryForMap("SELECT quality_score,delivery_score,price_score,overall_score,score_status FROM supplier WHERE id=?", sample.supplierId());
        assertEquals(6000, ((Number) supplier.get("quality_score")).intValue());
        assertEquals(7500, ((Number) supplier.get("delivery_score")).intValue());
        assertEquals(8889, ((Number) supplier.get("price_score")).intValue());
        assertEquals(7517, ((Number) supplier.get("overall_score")).intValue());
        assertEquals("READY", supplier.get("score_status"));
        for (int i = 0; i < 2; i++) {
            var sp = jdbc.queryForMap("SELECT quality_score,price_score,recommend_score,score_status FROM supplier_product WHERE id=?", sample.supplierProductIds().get(i));
            assertEquals(i == 0 ? 8000 : 5000, ((Number) sp.get("quality_score")).intValue());
            assertEquals(i == 0 ? 10000 : 8333, ((Number) sp.get("price_score")).intValue());
            assertEquals(i == 0 ? 8450 : 7050, ((Number) sp.get("recommend_score")).intValue());
            assertEquals("READY", sp.get("score_status"));
        }
    }

    /** 密码优先读取本机环境变量，缺失时安全读取开发配置，不在输出中打印配置内容。 */
    private static DataSource dataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("com.mysql.cj.jdbc.Driver");
        source.setUrl(System.getProperty("score.it.url", "jdbc:mysql://localhost:13307/erp?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        source.setUsername(System.getProperty("score.it.user", "root"));
        String password = System.getenv("ERP_IT_JDBC_PASSWORD");
        if (password == null || password.isBlank()) {
            YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource("../erp-admin/src/main/resources/application-dev.yml"));
            password = Objects.requireNonNull(yaml.getObject()).getProperty("spring.datasource.password");
            if (password != null && password.startsWith("${") && password.endsWith("}")) {
                String placeholder = password.substring(2, password.length() - 1);
                int separator = placeholder.indexOf(':');
                String configured = System.getenv(separator < 0 ? placeholder : placeholder.substring(0, separator));
                password = configured != null ? configured : separator < 0 ? null : placeholder.substring(separator + 1);
            }
        }
        source.setPassword(Objects.requireNonNull(password, "请设置 ERP_IT_JDBC_PASSWORD"));
        return source;
    }

    /** 最小真实评分应用：只排除与本次验证无关的 Web、AI 和每日任务。 */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({SupplierScoreRocketMQTemplate.class, SupplierScoreFireConsumer.class,
            ScoreRecalcPendingService.class, PendingRedisSupport.class,
            SupplierScoreFactsAggregationService.class, ScoreFactsQueryService.class,
            SupplierQualityAmountCacheService.class, QualityScoreCalculator.class,
            DeliveryScoreCalculator.class, PriceScoreCalculator.class, AggregateScoreCalculator.class,
            SupplierScoreRecalculateServiceImpl.class, SupplierScoreChangeLogServiceImpl.class})
    static class RealScoreConfiguration {
        @Bean DataSource source() { return dataSource(); }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
        @Bean(destroyMethod = "shutdown") RedissonClient redissonClient() {
            Config config = new Config();
            config.useSingleServer().setAddress(System.getProperty("score.it.redis", "redis://localhost:6379"));
            return Redisson.create(config);
        }
        @Bean StringRedisTemplate redis(RedissonClient client) { return new StringRedisTemplate(new RedissonConnectionFactory(client)); }
        @Bean BillNoGenerator billNoGenerator(StringRedisTemplate redis, RedissonClient client) { return new BillNoGenerator(redis, client); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true); configuration.setLogImpl(NoLoggingImpl.class);
            for (Class<?> mapper : List.of(SupplierMapper.class, SupplierProductMapper.class, ProductMapper.class,
                    PurchaseOrderMapper.class, PurchaseOrderItemMapper.class, InboundBillMapper.class,
                    InboundBillItemMapper.class, SupplierScoreChangeLogMapper.class)) configuration.addMapper(mapper);
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source); factory.setConfiguration(configuration);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
            return factory.getObject();
        }
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean SupplierMapper supplierMapper(SqlSessionTemplate s) { return s.getMapper(SupplierMapper.class); }
        @Bean SupplierProductMapper supplierProductMapper(SqlSessionTemplate s) { return s.getMapper(SupplierProductMapper.class); }
        @Bean ProductMapper productMapper(SqlSessionTemplate s) { return s.getMapper(ProductMapper.class); }
        @Bean PurchaseOrderMapper orderMapper(SqlSessionTemplate s) { return s.getMapper(PurchaseOrderMapper.class); }
        @Bean PurchaseOrderItemMapper orderItemMapper(SqlSessionTemplate s) { return s.getMapper(PurchaseOrderItemMapper.class); }
        @Bean InboundBillMapper inboundMapper(SqlSessionTemplate s) { return s.getMapper(InboundBillMapper.class); }
        @Bean InboundBillItemMapper inboundItemMapper(SqlSessionTemplate s) { return s.getMapper(InboundBillItemMapper.class); }
        @Bean SupplierScoreChangeLogMapper logs(SqlSessionTemplate s) { return s.getMapper(SupplierScoreChangeLogMapper.class); }
    }
}
