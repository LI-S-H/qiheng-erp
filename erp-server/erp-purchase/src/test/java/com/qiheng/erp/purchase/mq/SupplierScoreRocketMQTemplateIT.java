package com.qiheng.erp.purchase.mq;

import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import java.sql.DriverManager;
import java.util.List;
import java.util.Objects;
import org.redisson.Redisson;
import org.redisson.config.Config;
import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;

/** 核对真实云端集群和 Topic，不投递业务事件；仅显式 repair 时回收本轮独立测试组。 */
@EnabledIfSystemProperty(named = "mq.real.it", matches = "true")
class SupplierScoreRocketMQTemplateIT {
    /** 只读核对指定运行的数据、Redis 键、测试 Topic 和消费组均已回收。 */
    @Test
    @EnabledIfSystemProperty(named = "mq.it.cleanup.run", matches = "[a-zA-Z0-9]{16}")
    void isolatedResourcesShouldHaveBeenCleaned() throws Exception {
        String runId = System.getProperty("mq.it.cleanup.run");
        String topic = "erp-score-it-" + runId;
        String group = "erp-score-it-consumer-" + runId;
        String url = System.getProperty("score.it.url", "jdbc:mysql://localhost:13307/erp?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        try (var connection = DriverManager.getConnection(url, System.getProperty("score.it.user", "root"),
                Objects.requireNonNull(System.getenv("ERP_IT_JDBC_PASSWORD")))) {
            for (String table : List.of("supplier", "product", "supplier_product", "purchase_order", "purchase_order_item", "inbound_bill", "inbound_bill_item")) {
                try (var query = connection.prepareStatement("SELECT COUNT(*) FROM `" + table + "` WHERE remark=?")) {
                    query.setString(1, "隔离评分消息测试 " + runId);
                    try (var result = query.executeQuery()) { assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isZero(); }
                }
            }
            try (var query = connection.prepareStatement("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id IN (?,?,?)")) {
                String[] ids = System.getProperty("mq.it.cleanup.suppliers").split(",");
                assertThat(ids).hasSize(3);
                for (int i = 0; i < 3; i++) query.setLong(i + 1, Long.parseLong(ids[i]));
                try (var result = query.executeQuery()) { assertThat(result.next()).isTrue(); assertThat(result.getInt(1)).isZero(); }
            }
        }
        Config redisConfig = new Config();
        redisConfig.useSingleServer().setAddress(System.getProperty("score.it.redis", "redis://localhost:6379"));
        var redis = Redisson.create(redisConfig);
        try {
            for (String id : System.getProperty("mq.it.cleanup.suppliers").split(",")) {
                Long supplierId = Long.parseLong(id);
                assertThat(redis.getBucket(SupplierScoreRedisKeys.pendingKey(supplierId)).isExists()).isFalse();
                assertThat(redis.getBucket(SupplierScoreRedisKeys.qualityAmountKey(supplierId)).isExists()).isFalse();
                assertThat(redis.getLock(SupplierScoreRedisKeys.lockKey(supplierId)).isLocked()).isFalse();
            }
        } finally { redis.shutdown(); }
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RocketMQAutoConfiguration.class))
                .withUserConfiguration(SupplierScoreRocketMQTemplate.class)
                .withPropertyValues("rocketmq.name-server=" + System.getProperty("mq.it.name-server", "8.154.27.144:9876"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var producer = context.getBean(SupplierScoreRocketMQTemplate.class).getProducer();
                    var api = producer.getDefaultMQProducerImpl().getMqClientFactory().getMQClientAPIImpl();
                    for (String owned : List.of(topic, "%RETRY%" + group, "%DLQ%" + group)) {
                        try {
                            api.getTopicRouteInfoFromNameServer(owned, 5000);
                            throw new AssertionError("测试 Topic 仍有路由：" + owned);
                        } catch (MQClientException e) { assertThat(e.getResponseCode()).isEqualTo(17); }
                    }
                    var brokers = api.getBrokerClusterInfo(5000).getBrokerAddrTable();
                    String broker = brokers.values().iterator().next().getBrokerAddrs().get(0L);
                    if (Boolean.getBoolean("mq.it.cleanup.repair")) {
                        SupplierScoreMqClosedLoopIT.deleteTestSubscriptionGroup(producer, broker, group);
                        String extra = System.getProperty("mq.it.cleanup.extra-run");
                        if (extra != null) SupplierScoreMqClosedLoopIT.deleteTestSubscriptionGroup(producer, broker,
                                "erp-score-it-consumer-" + extra);
                    }
                    assertThat(api.getAllSubscriptionGroup(broker, 5000).getSubscriptionGroupTable()).doesNotContainKey(group);
                    assertThat(api.getTopicRouteInfoFromNameServer("erp-supplier-score-recalc", 5000).getBrokerDatas()).isNotEmpty();
                    System.out.println("MQ_CLEANUP_VERIFIED run=" + runId + " dbRows=0 redisKeys=0 testRoutes=0 testGroupAbsent=true productionRouteRetained=true");
                });
    }

    @Test
    void annotatedProducerShouldReadActualCloudClusterAndTopicRoute() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RocketMQAutoConfiguration.class))
                .withUserConfiguration(SupplierScoreRocketMQTemplate.class)
                .withPropertyValues("rocketmq.name-server=" + System.getProperty("mq.it.name-server", "8.154.27.144:9876"),
                        "supplier-score.rocketmq.producer.group=erp-supplier-score-producer")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var producer = context.getBean(SupplierScoreRocketMQTemplate.class).getProducer();
                    assertThat(producer.getProducerGroup()).isEqualTo("erp-supplier-score-producer");
                    var api = producer.getDefaultMQProducerImpl().getMqClientFactory().getMQClientAPIImpl();
                    var brokers = api.getBrokerClusterInfo(5000).getBrokerAddrTable();
                    assertThat(brokers).isNotEmpty();
                    System.out.println("MQ_REAL_OK group=" + producer.getProducerGroup() + " brokers=" + brokers);
                    try {
                        var route = api.getTopicRouteInfoFromNameServer("erp-supplier-score-recalc", 5000);
                        assertThat(route.getBrokerDatas()).isNotEmpty();
                        System.out.println("MQ_REAL_TOPIC_OK route=" + route);
                    } catch (MQClientException e) {
                        // Topic 需运维显式初始化；集群可达不等于业务投递消费已验证。
                        if (e.getResponseCode() != 17) throw e;
                        System.out.println("MQ_REAL_TOPIC_MISSING topic=erp-supplier-score-recalc");
                    }
                });
    }
}
