package com.qiheng.erp.purchase.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.purchase.mapper.SupplierScoreChangeLogMapper;
import com.qiheng.erp.purchase.service.support.ScoreRecalcPendingService;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.ServiceState;
import org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 通过真实 Starter 验证注解注册、公共参数、具体类型注入及客户端关闭，不投递消息。 */
class SupplierScoreRocketMQTemplateTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RocketMQAutoConfiguration.class))
            .withUserConfiguration(SupplierScoreRocketMQTemplate.class)
            .withPropertyValues("rocketmq.name-server=127.0.0.1:65535");

    @Test
    void actualTemplateConvertsObjectAndHeadersIntoNativeDelayedMessage() throws Exception {
        // 使用真实 Template 和官方转换器，仅 mock 最终网络发送，不启动客户端或替换已启动的生产者。
        SupplierScoreRocketMQTemplate template = new SupplierScoreRocketMQTemplate();
        DefaultMQProducer producer = mock(DefaultMQProducer.class);
        template.setProducer(producer);
        var converter = new org.apache.rocketmq.spring.support.RocketMQMessageConverter().getMessageConverter();
        template.setMessageConverter(converter);
        var payload = new com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreFireMessage();
        // 大于 JavaScript 安全整数范围的 ID 必须在框架往返转换中保持 Long 精度。
        payload.setMsgType("SCHEDULED_FIRE"); payload.setSupplierId(1791396328523123456L);
        payload.setBatchNo("SC2026100600001"); payload.setScheduledFireAt(1791396328523L);
        var springMessage = org.springframework.messaging.support.MessageBuilder.withPayload(payload)
                .setHeader(org.apache.rocketmq.spring.support.RocketMQHeaders.KEYS, payload.getBatchNo()).build();
        org.mockito.Mockito.when(producer.send(org.mockito.ArgumentMatchers.any(org.apache.rocketmq.common.message.Message.class),
                org.mockito.ArgumentMatchers.eq(4321L))).thenReturn(mock(org.apache.rocketmq.client.producer.SendResult.class));
        template.syncSend("erp-supplier-score-recalc:SCHEDULED_FIRE", springMessage, 4321L, 9);
        var actual = org.mockito.ArgumentCaptor.forClass(org.apache.rocketmq.common.message.Message.class);
        org.mockito.Mockito.verify(producer).send(actual.capture(), org.mockito.ArgumentMatchers.eq(4321L));
        assertThat(actual.getValue().getTopic()).isEqualTo("erp-supplier-score-recalc");
        assertThat(actual.getValue().getTags()).isEqualTo("SCHEDULED_FIRE");
        assertThat(actual.getValue().getKeys()).isEqualTo(payload.getBatchNo());
        assertThat(actual.getValue().getDelayTimeLevel()).isEqualTo(9);
        var json = new ObjectMapper().readTree(actual.getValue().getBody());
        assertThat(json.get("batchNo").asText()).isEqualTo(payload.getBatchNo());
        assertThat(json.has("batchToken")).isFalse();
        assertThat(json.get("msgType").asText()).isEqualTo("SCHEDULED_FIRE");
        assertThat(json.get("supplierId").longValue()).isEqualTo(payload.getSupplierId());
        assertThat(json.get("scheduledFireAt").longValue()).isEqualTo(payload.getScheduledFireAt());
        // 与消费者使用同类框架转换器，验证网络字节可还原成原消息对象。
        var received = org.springframework.messaging.support.MessageBuilder
                .withPayload(actual.getValue().getBody()).build();
        var restored = (com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreFireMessage)
                converter.fromMessage(received, com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreFireMessage.class);
        assertThat(restored).isNotNull();
        assertThat(restored.getSupplierId()).isEqualTo(payload.getSupplierId());
        assertThat(restored.getScheduledFireAt()).isEqualTo(payload.getScheduledFireAt());
        assertThat(restored.getBatchNo()).isEqualTo(payload.getBatchNo());
        assertThat(restored.getMsgType()).isEqualTo(payload.getMsgType());
    }

    @Test
    void annotationShouldRegisterProducerWithoutDefaultGroupAndCloseIt() {
        AtomicReference<DefaultMQProducer> producerRef = new AtomicReference<>();
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SupplierScoreRocketMQTemplate.class)
                    .doesNotHaveBean("defaultMQProducer");
            DefaultMQProducer producer = context.getBean(SupplierScoreRocketMQTemplate.class).getProducer();
            producerRef.set(producer);
            assertThat(producer.getProducerGroup()).isEqualTo("supplier-score-producer");
            assertThat(producer.getNamesrvAddr()).isEqualTo("127.0.0.1:65535");
            assertThat(producer.getDefaultMQProducerImpl().getServiceState()).isEqualTo(ServiceState.RUNNING);
        });
        assertThat(producerRef.get().getDefaultMQProducerImpl().getServiceState())
                .isEqualTo(ServiceState.SHUTDOWN_ALREADY);
    }

    @Test
    void annotationShouldResolveBusinessGroupAndInheritSharedSendParameters() {
        runner.withPropertyValues("supplier-score.rocketmq.producer.group=score-custom-test",
                "rocketmq.producer.send-message-timeout=4321",
                "rocketmq.producer.retry-times-when-send-failed=3",
                "rocketmq.producer.retry-times-when-send-async-failed=4").run(context -> {
            assertThat(context).hasNotFailed();
            DefaultMQProducer producer = context.getBean(SupplierScoreRocketMQTemplate.class).getProducer();
            assertThat(producer.getProducerGroup()).isEqualTo("score-custom-test");
            assertThat(producer.getSendMsgTimeout()).isEqualTo(4321);
            assertThat(producer.getRetryTimesWhenSendFailed()).isEqualTo(3);
            assertThat(producer.getRetryTimesWhenSendAsyncFailed()).isEqualTo(4);
        });
    }

    @Test
    void pendingServiceShouldInjectScoreTypeWhenAnotherTemplateExists() {
        runner.withUserConfiguration(ScoreRecalcPendingService.class)
                .withBean("otherRocketMQTemplate", RocketMQTemplate.class, () -> mock(RocketMQTemplate.class))
                .withBean(StringRedisTemplate.class, () -> mock(StringRedisTemplate.class))
                .withBean(RedissonClient.class, () -> mock(RedissonClient.class))
                .withBean(BillNoGenerator.class, () -> mock(BillNoGenerator.class))
                .withBean(SupplierScoreChangeLogMapper.class, () -> mock(SupplierScoreChangeLogMapper.class))
                .withBean(ObjectMapper.class, ObjectMapper::new).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ScoreRecalcPendingService.class);
                    assertThat(ReflectionTestUtils.getField(context.getBean(ScoreRecalcPendingService.class), "rocketMQTemplate"))
                            .isSameAs(context.getBean(SupplierScoreRocketMQTemplate.class));
                });
    }
}
