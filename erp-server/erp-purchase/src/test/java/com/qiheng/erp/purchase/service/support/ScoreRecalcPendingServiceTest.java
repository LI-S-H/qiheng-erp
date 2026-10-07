package com.qiheng.erp.purchase.service.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreEventTrigger;
import com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreFireMessage;
import com.qiheng.erp.purchase.mapper.SupplierScoreChangeLogMapper;
import com.qiheng.erp.purchase.mq.SupplierScoreRocketMQTemplate;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.spring.support.RocketMQHeaders;
import org.springframework.messaging.Message;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 验证冷启动来源合并、触发枚举序列化以及投递失败后的 pending 清理。 */
@ExtendWith(MockitoExtension.class)
class ScoreRecalcPendingServiceTest {
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private RedissonClient redisson;
    @Mock private RLock lock;
    @Mock private SupplierScoreRocketMQTemplate mq;
    @Mock private DefaultMQProducer producer;
    @Mock private BillNoGenerator billNoGenerator;
    @Mock private SupplierScoreChangeLogMapper changeLogMapper;

    private ScoreRecalcPendingService service;

    @BeforeEach
    void setUp() {
        service = new ScoreRecalcPendingService(redis, redisson, mq, new ObjectMapper(), billNoGenerator, changeLogMapper);
        ReflectionTestUtils.setField(service, "mqTopic", "erp-supplier-score-recalc");
        when(redisson.getLock(any(String.class))).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        when(redis.opsForValue()).thenReturn(values);
        when(mq.getProducer()).thenReturn(producer);
        when(producer.getSendMsgTimeout()).thenReturn(3000);
        when(billNoGenerator.nextNo(eq("SC"), any())).thenReturn("SC2026100600001", "SC2026100600002");
    }

    @Test
    void firstEventIsSentSynchronouslyWithFiveMinuteDelay() throws Exception {
        var event = trigger();
        assertEquals("SC2026100600001", service.mergeAndScheduleFire(event));

        org.mockito.ArgumentCaptor<Message> message = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(mq).syncSend(eq("erp-supplier-score-recalc:SCHEDULED_FIRE"), message.capture(), eq(3000L), eq(9));
        assertEquals("SC2026100600001", message.getValue().getHeaders().get(RocketMQHeaders.KEYS));
        var mapper = new ObjectMapper();
        byte[] body = (byte[]) message.getValue().getPayload();
        var payload = mapper.readValue(body, SupplierScoreFireMessage.class);
        assertEquals("SC2026100600001", payload.getBatchNo());
        assertEquals("SCHEDULED_FIRE", payload.getMsgType());
        assertEquals(7L, payload.getSupplierId());
        assertFalse(mapper.readTree(body).has("batchToken"));
        assertEquals("SC2026100600001", mapper.readTree(body).get("batchNo").asText());
        verify(billNoGenerator).nextNo(eq("SC"), any());
        verify(producer, never()).send(any(org.apache.rocketmq.common.message.Message.class));
        verify(redis, never()).delete(any(String.class));
        verify(lock).unlock();

        // 枚举仍以既有字符串值序列化，来源主键和单号不因删除冗余类型而丢失。
        String eventJson = mapper.writeValueAsString(event);
        assertEquals("INBOUND_TRIGGER", mapper.readTree(eventJson).get("triggerType").asText());
        assertFalse(mapper.readTree(eventJson).has("sourceRefType"));
        var restored = mapper.readValue(eventJson, SupplierScoreEventTrigger.class);
        assertEquals(TriggerType.INBOUND_TRIGGER, restored.getTriggerType());
        assertEquals(101L, restored.getSourceRefId());
        assertEquals("IN-101", restored.getSourceRefNo());
    }

    @Test
    void coldPendingMergesAllOrdersAndRoundTripsEnumWithoutRemovedField() throws Exception {
        // 模拟清空开发 pending 后冷启动，两次业务事件通过真实 JSON 保存和读取合并。
        var stored = new java.util.concurrent.atomic.AtomicReference<String>();
        when(values.get("supplier:score:pending:7")).thenAnswer(invocation -> stored.get());
        doAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return null;
        }).when(values).set(org.mockito.ArgumentMatchers.eq("supplier:score:pending:7"),
                any(String.class), any(java.time.Duration.class));

        String firstToken = service.mergeAndScheduleFire(trigger());
        var second = trigger();
        second.setSourceRefId(102L);
        second.setSourceRefNo("IN-102");
        assertEquals(firstToken, service.mergeAndScheduleFire(second));

        var mapper = new ObjectMapper();
        var saved = mapper.readValue(stored.get(), ScoreRecalcPendingService.PendingSnapshot.class);
        assertEquals("SC2026100600001", saved.getBatchNo());
        assertFalse(mapper.readTree(stored.get()).has("batchToken"));
        assertEquals(2, saved.getMergedCount());
        assertEquals(java.util.List.of(101L, 102L), saved.getSourceRefs().stream()
                .map(ScoreRecalcPendingService.PendingSnapshot.SourceRef::getSourceRefId).toList());
        assertEquals(java.util.List.of("IN-101", "IN-102"), saved.getSourceRefs().stream()
                .map(ScoreRecalcPendingService.PendingSnapshot.SourceRef::getSourceRefNo).toList());
        for (var ref : mapper.readTree(stored.get()).get("sourceRefs")) {
            assertEquals("INBOUND_TRIGGER", ref.get("triggerType").asText());
            assertFalse(ref.has("sourceRefType"));
        }
        assertEquals(java.util.List.of(TriggerType.INBOUND_TRIGGER, TriggerType.INBOUND_TRIGGER),
                saved.getSourceRefs().stream()
                        .map(ScoreRecalcPendingService.PendingSnapshot.SourceRef::getTriggerType).toList());
        verify(mq, times(1)).syncSend(eq("erp-supplier-score-recalc:SCHEDULED_FIRE"), any(Message.class), eq(3000L), eq(9));
        verify(billNoGenerator, times(1)).nextNo(eq("SC"), any());
        verify(lock, times(2)).unlock();
    }

    @Test
    void failedSendClearsPendingForNextEvent() throws Exception {
        when(mq.syncSend(anyString(), any(Message.class), eq(3000L), eq(9)))
                .thenThrow(new IllegalStateException("broker unavailable"));

        assertThrows(IllegalStateException.class, () -> service.mergeAndScheduleFire(trigger()));

        verify(redis).delete("supplier:score:pending:7");
        verify(redis).delete("supplier:score:quality:7");
        verify(lock).unlock();
    }

    @Test
    void staleWindowCarriesOldOrderIntoNewFullRebuildBatch() throws Exception {
        var old = new ScoreRecalcPendingService.PendingSnapshot();
        old.setBatchNo("SC2026100600000");
        old.setScheduledFireAt(System.currentTimeMillis() - 600_000L);
        var oldRef = new ScoreRecalcPendingService.PendingSnapshot.SourceRef();
        oldRef.setSourceRefId(100L);
        old.setSourceRefs(java.util.List.of(oldRef));
        when(values.get("supplier:score:pending:7")).thenReturn(new ObjectMapper().writeValueAsString(old));

        service.mergeAndScheduleFire(trigger());

        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(org.mockito.ArgumentMatchers.eq("supplier:score:pending:7"), json.capture(),
                org.mockito.ArgumentMatchers.any(java.time.Duration.class));
        var saved = new ObjectMapper().readValue(json.getValue(), ScoreRecalcPendingService.PendingSnapshot.class);
        assertEquals("SC2026100600001", saved.getBatchNo());
        org.junit.jupiter.api.Assertions.assertEquals(2, saved.getSourceRefs().size());
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(100L, 101L),
                saved.getSourceRefs().stream().map(ScoreRecalcPendingService.PendingSnapshot.SourceRef::getSourceRefId).toList());
    }

    @Test
    void differentSuppliersAllocateDifferentBusinessBatches() {
        var second = trigger();
        second.setSupplierId(8L);
        assertEquals("SC2026100600001", service.mergeAndScheduleFire(trigger()));
        assertEquals("SC2026100600002", service.mergeAndScheduleFire(second));
        verify(billNoGenerator, times(2)).nextNo(eq("SC"), any());
        verify(mq, times(2)).syncSend(anyString(), any(Message.class), eq(3000L), eq(9));
    }

    private SupplierScoreEventTrigger trigger() {
        SupplierScoreEventTrigger trigger = new SupplierScoreEventTrigger();
        trigger.setSupplierId(7L);
        trigger.setTriggerType(TriggerType.INBOUND_TRIGGER);
        trigger.setSourceRefId(101L);
        trigger.setSourceRefNo("IN-101");
        return trigger;
    }
}
