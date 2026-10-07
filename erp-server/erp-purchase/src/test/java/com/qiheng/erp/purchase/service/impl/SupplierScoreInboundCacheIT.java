package com.qiheng.erp.purchase.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;
import com.qiheng.erp.purchase.domain.supplierscore.dto.FactsSnapshot;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreRecalcContext;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import com.qiheng.erp.purchase.domain.supplierscore.mq.SupplierScoreFireMessage;
import com.qiheng.erp.purchase.mq.SupplierScoreFireConsumer;
import com.qiheng.erp.purchase.mq.SupplierScoreMqFixture;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import com.qiheng.erp.purchase.service.scoring.ScoreFactsQueryService;
import com.qiheng.erp.purchase.service.scoring.SupplierQualityAmountCacheService;
import com.qiheng.erp.purchase.service.support.PendingRedisSupport;
import com.qiheng.erp.purchase.service.support.ScoreRecalcPendingService;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实 MySQL、Redis 入库增量回归：只创建唯一夹具，不调用正式业务单据或共享 Topic。
 * SQL 跟踪只观察真实 MyBatis 查询，不模拟数据库结果；分数与金额由独立常量和 JDBC 汇总核对。
 * 消费重试用例直接调用真实消费者，不将其冒充云端消息投递验证。
 */
@EnabledIfSystemProperty(named = "score.inbound.cache.it", matches = "true")
class SupplierScoreInboundCacheIT {
    private AnnotationConfigApplicationContext context;
    private JdbcTemplate jdbc;
    private StringRedisTemplate redis;
    private SupplierScoreMqFixture fixture;
    private SupplierScoreMqFixture.Sample sample;
    private SupplierScoreRecalculateService recalculate;
    private ScoreFactsQueryService facts;
    private SupplierQualityAmountCacheService qualityCache;
    private QueryTracker tracker;
    private ExecutorService workers;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

    @BeforeEach
    void createOwnedFactsAndCacheBaseline() {
        context = new AnnotationConfigApplicationContext(RealConfiguration.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        redis = context.getBean(StringRedisTemplate.class);
        fixture = new SupplierScoreMqFixture(jdbc, UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        sample = fixture.create().stream().filter(s -> s.label().equals("merged")).findFirst().orElseThrow();
        recalculate = context.getBean(SupplierScoreRecalculateService.class);
        facts = context.getBean(ScoreFactsQueryService.class);
        qualityCache = context.getBean(SupplierQualityAmountCacheService.class);
        tracker = context.getBean(QueryTrackingPostProcessor.class).tracker;
        workers = Executors.newFixedThreadPool(2);
        // 第二单只包含第一个产品，明确区分受影响与不受影响产品；所有删除均为夹具预留主键。
        jdbc.update("DELETE FROM inbound_bill_item WHERE id=?", sample.inboundItemIds().get(3));
        jdbc.update("DELETE FROM purchase_order_item WHERE id=?", sample.orderItemIds().get(3));
        jdbc.update("UPDATE purchase_order SET total_amount=100000,status='APPROVED',fully_received_at=NULL WHERE id=?", sample.orderIds().get(1));
        jdbc.update("UPDATE inbound_bill_item SET qualified_qty=1000,defective_qty=0 WHERE id=?", sample.inboundItemIds().get(2));
        locked(() -> recalculate.recalcFactsForSupplier(dailyContext(), today));
        assertEquals(6000, supplierScore("quality_score"));
        assertEquals(7500, supplierScore("delivery_score"));
        assertEquals(8889, supplierScore("price_score"));
        assertEquals(7517, supplierScore("overall_score"));
        assertAmounts(18000000, 12000000, today);
        var baselineDelivery = locked(() -> facts.queryFactsWithoutCache(sample.supplierId(), today));
        assertEquals(400000L, baselineDelivery.getDueAmount(), "新订单已审核且到期，完全入库前就必须纳入应交金额");
        assertEquals(0, BigDecimal.valueOf(100000).compareTo(baselineDelivery.getPenaltyAmountRaw()));
        // 只模拟本次夹具订单首次转为完全入库，仓库库存不在本次测试范围。
        jdbc.update("UPDATE purchase_order SET status='INBOUND_DONE',fully_received_at=? WHERE id=?",
                Timestamp.valueOf(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))), sample.orderIds().get(1));
        jdbc.update("DELETE FROM supplier_score_change_log WHERE supplier_id=?", sample.supplierId());
        assertIndependentDeliveryTotals();
        tracker.clear();
    }

    @AfterEach
    void removeOnlyOwnedRowsAndCaches() throws InterruptedException {
        try {
            if (workers != null) {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "不能在本测试事务仍运行时清理数据");
            }
            if (fixture != null && context != null) {
                for (var owned : fixture.samples()) {
                    var lock = context.getBean(RedissonClient.class).getLock(SupplierScoreRedisKeys.lockKey(owned.supplierId()));
                    assertTrue(lock.tryLock(10, TimeUnit.SECONDS));
                    try {
                        redis.delete(List.of(SupplierScoreRedisKeys.qualityAmountKey(owned.supplierId()),
                                SupplierScoreRedisKeys.pendingKey(owned.supplierId())));
                    } finally { lock.unlock(); }
                }
                fixture.cleanup();
                for (var owned : fixture.samples()) {
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM supplier WHERE id=?", Integer.class, owned.supplierId()));
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?", Integer.class, owned.supplierId()));
                    assertFalse(Boolean.TRUE.equals(redis.hasKey(SupplierScoreRedisKeys.qualityAmountKey(owned.supplierId()))));
                    assertFalse(Boolean.TRUE.equals(redis.hasKey(SupplierScoreRedisKeys.pendingKey(owned.supplierId()))));
                }
            }
        } finally { if (context != null) context.close(); }
    }

    @Test
    void warmMergedAndDuplicateOrdersShouldOnlyQueryAffectedProductsAndReuseStoredDelivery() {
        Map<String, Object> untouched = productRow(1);
        List<Map<String, Object>> basis = basisRows();
        inbound(List.of(sample.orderIds().getFirst(), sample.orderIds().get(1), sample.orderIds().get(1)));
        assertExpectedCompletedScores();
        assertAmounts(28000000, 12000000, today);
        assertEquals(0, tracker.deliveryWindowQueries(), "已有交付分的正常增量不能扫描交付窗口");
        assertTrue(tracker.events.stream().anyMatch(e -> e.id().endsWith("selectQualityOrdersForProducts")), "暖缓存必须真实查询受影响产品窗口");
        // 第一单也在合并集合中，其两个产品均属于本批影响范围，但不应重复追加第一单金额。
        assertEquals(untouched, productRow(1));
        assertEquals(basis, basisRows(), "重算不能覆盖历史累计金额权重");
        assertIndependentQualityTotals(28000000, 12000000);
        assertIndependentDeliveryTotals();
        explainActualFilteredSql();
        int logs = logCount();
        Map<String, Object> supplier = supplierRow();
        List<Map<String, Object>> products = productRows();
        inbound(List.of(sample.orderIds().get(1), sample.orderIds().getFirst()));
        assertAmounts(28000000, 12000000, today);
        assertEquals(logs, logCount(), "已应用订单重投不能重复写指标日志");
        assertEquals(supplier, supplierRow());
        assertEquals(products, productRows());
        assertTrue(Boolean.TRUE.equals(redis.opsForHash().hasKey(SupplierScoreRedisKeys.qualityAmountKey(sample.supplierId()), "applied:" + sample.orderIds().get(1))));
    }

    @Test
    void singleAffectedProductShouldPreserveOtherQualityPriceRecommendationAndVersion() {
        Map<String, Object> untouched = productRow(1);
        inbound(List.of(sample.orderIds().get(1)));
        assertExpectedCompletedScores();
        assertEquals(untouched, productRow(1), "部分质量快照不能把未查询产品质量、价格或推荐分清空");
        assertEquals(0, tracker.deliveryWindowQueries());
        var partial = locked(() -> facts.queryInboundFacts(sample.supplierId(), today, List.of(sample.orderIds().get(1))));
        assertFalse(partial.isFullQualityRebuild());
        assertEquals(Set.of(sample.supplierProductIds().getFirst()), partial.getAffectedSupplierProductIds());
        assertEquals(Set.of(sample.supplierProductIds().getFirst()), partial.getProductQualityAmounts().keySet());
        assertIndependentQualityTotals(28000000, 12000000);
        assertIndependentDeliveryTotals();
        explainActualFilteredSql();
    }

    @Test
    void inboundShouldRecalculateTargetPriceAndAggregateCommittedAmountWeightsWithoutAddingAgain() {
        // 只改本测试行，模拟仓库已提交新单100000分金额；评分负责使用该权重，不能再次累计。
        jdbc.update("UPDATE supplier SET score_basis_amount=score_basis_amount+100000 WHERE id=?", sample.supplierId());
        jdbc.update("UPDATE supplier_product SET score_basis_amount=score_basis_amount+100000,quoted_purchase_price=15000 WHERE id=?",
                sample.supplierProductIds().getFirst());
        // 当前有效报价150元、参考价100元，存储的10000旧价格分需要本次入库重算校正。
        assertEquals(10000, productScore(0, "price_score"));
        Map<String, Object> untouched = productRow(1);
        List<Map<String, Object>> committedBasis = basisRows();
        inbound(List.of(sample.orderIds().get(1)));
        assertEquals(6667, productScore(0, "price_score"));
        assertEquals(9000, productScore(0, "quality_score"));
        assertEquals(7750, productScore(0, "recommend_score"));
        // (110000*6667 + 20000*8333)/130000 = 6923.30769，最终四舍五入为6923。
        assertEquals(6923, supplierScore("price_score"));
        assertEquals(7000, supplierScore("quality_score"));
        assertEquals(7500, supplierScore("delivery_score"));
        assertEquals(7227, supplierScore("overall_score"));
        assertEquals(untouched, productRow(1));
        assertEquals(committedBasis, basisRows());
        assertEquals(130000L, jdbc.queryForObject("SELECT score_basis_amount FROM supplier WHERE id=?", Long.class, sample.supplierId()));
        assertAmounts(28000000, 12000000, today);
        assertIndependentQualityTotals(28000000, 12000000);
        assertIndependentDeliveryTotals();
        assertEquals(6667, jdbc.queryForObject("SELECT metric_score_after FROM supplier_score_change_log "
                + "WHERE supplier_id=? AND supplier_product_id=? AND metric_type='PRICE'", Integer.class,
                sample.supplierId(), sample.supplierProductIds().getFirst()));
        int logs = logCount();
        inbound(List.of(sample.orderIds().get(1)));
        assertEquals(logs, logCount());
        assertEquals(committedBasis, basisRows());
        assertEquals(130000L, jdbc.queryForObject("SELECT score_basis_amount FROM supplier WHERE id=?", Long.class, sample.supplierId()));
        assertAmounts(28000000, 12000000, today);
    }

    @Test
    void nullStoredDeliveryShouldQueryRealDeliveryWithoutRebuildingUnrelatedQuality() {
        jdbc.update("UPDATE supplier SET delivery_score=NULL WHERE id=?", sample.supplierId());
        jdbc.update("UPDATE supplier_product SET recommend_score=NULL WHERE supplier_id=?", sample.supplierId());
        inbound(List.of(sample.orderIds().get(1)));
        assertExpectedCompletedScores();
        assertTrue(tracker.deliveryWindowQueries() > 0, "缺失交付分需要只补查真实交付事实");
        assertFalse(tracker.events.stream().anyMatch(e -> e.sql().contains("fully_received_at >=")
                && !e.id().endsWith("selectQualityOrdersForProducts")), "已有质量缓存不能因为交付缺失重建全质量窗口");
        assertTrue(tracker.events.stream().anyMatch(e -> e.id().endsWith("selectQualityOrdersForProducts")));
        assertAmounts(28000000, 12000000, today);
        assertIndependentQualityTotals(28000000, 12000000);
        assertIndependentDeliveryTotals();
    }

    @Test
    void zeroStoredDeliveryShouldRemainZeroWithoutQueryingHistory() {
        jdbc.update("UPDATE supplier SET delivery_score=0 WHERE id=?", sample.supplierId());
        inbound(List.of(sample.orderIds().get(1)));
        assertEquals(0, supplierScore("delivery_score"));
        assertEquals(0, tracker.deliveryWindowQueries(), "合法零分不是交付分缺失");
        assertEquals(7000, supplierScore("quality_score"));
        assertEquals(8889, supplierScore("price_score"));
        assertAmounts(28000000, 12000000, today);
    }

    @Test
    void nullStoredDeliveryWithoutDueOrdersShouldRemainNullAfterRealQuery() {
        jdbc.update("UPDATE supplier SET delivery_score=NULL WHERE id=?", sample.supplierId());
        for (Long orderId : sample.orderIds()) {
            jdbc.update("UPDATE purchase_order SET expected_arrival_date=? WHERE id=?", today.plusDays(1), orderId);
        }
        inbound(List.of(sample.orderIds().get(1)));
        assertNull(jdbc.queryForObject("SELECT delivery_score FROM supplier WHERE id=?", Integer.class, sample.supplierId()));
        assertTrue(tracker.deliveryWindowQueries() > 0, "缺失交付分必须真实核对有无到期样本");
        assertEquals(7000, supplierScore("quality_score"));
        assertEquals(8889, supplierScore("price_score"));
        assertAmounts(28000000, 12000000, today);
        var full = locked(() -> facts.queryFactsWithoutCache(sample.supplierId(), today));
        assertEquals(0L, full.getDueAmount());
        assertEquals(0, BigDecimal.ZERO.compareTo(full.getPenaltyAmountRaw()));
    }

    @Test
    void coldRebuildShouldIncludeEveryOrderExactlyOnceAndMatchFullFacts() {
        redis.delete(SupplierScoreRedisKeys.qualityAmountKey(sample.supplierId()));
        var snapshot = locked(() -> facts.queryInboundFacts(sample.supplierId(), today, sample.orderIds()));
        assertTrue(snapshot.isFullQualityRebuild());
        assertEquals(2, snapshot.getProductQualityAmounts().size());
        assertEquals(BigInteger.valueOf(28000000), snapshot.getQualifiedAmountRaw());
        assertEquals(BigInteger.valueOf(12000000), snapshot.getDefectiveAmountRaw());
        var full = locked(() -> facts.queryFactsWithoutCache(sample.supplierId(), today));
        assertSameAmounts(snapshot, full);
        inbound(sample.orderIds());
        assertExpectedCompletedScores();
        assertAmounts(28000000, 12000000, today);
        for (Long id : sample.orderIds()) assertTrue(Boolean.TRUE.equals(redis.opsForHash().hasKey(
                SupplierScoreRedisKeys.qualityAmountKey(sample.supplierId()), "applied:" + id)));
        assertIndependentQualityTotals(28000000, 12000000);
        assertCacheTtl();
    }

    @Test
    void dailyDeliveryChangeShouldUpdateAllRecommendationsWithoutClearingOtherQualityOrPrice() {
        // 历史交付变化留给每日校正，不使用普通入库强制刷新。
        for (Long id : sample.inboundIds()) jdbc.update("UPDATE inbound_bill SET confirmed_at=? WHERE id=?",
                Timestamp.valueOf(today.minusDays(1).atTime(12, 0)), id);
        inbound(List.of(sample.orderIds().get(1)));
        assertEquals(7500, supplierScore("delivery_score"), "正常入库沿用旧分，即使历史交付事实已改变");
        assertEquals(0, tracker.deliveryWindowQueries());
        jdbc.update("DELETE FROM supplier_score_change_log WHERE supplier_id=?", sample.supplierId());
        locked(() -> recalculate.recalcFactsForSupplier(dailyContext(), today));
        assertEquals(10000, supplierScore("delivery_score"));
        assertEquals(7000, supplierScore("quality_score"));
        assertEquals(8889, supplierScore("price_score"));
        assertEquals(8567, supplierScore("overall_score"));
        assertEquals(9500, productScore(0, "recommend_score"));
        assertEquals(7800, productScore(1, "recommend_score"));
        assertEquals(5000, productScore(1, "quality_score"));
        assertEquals(8333, productScore(1, "price_score"));
        assertEquals(List.of("DELIVERY"), jdbc.queryForList(
                "SELECT DISTINCT metric_type FROM supplier_score_change_log WHERE supplier_id=? AND supplier_product_id=?",
                String.class, sample.supplierId(), sample.supplierProductIds().get(1)), "仅交付引起的推荐变化不能伪记质量或价格");
        assertTrue(tracker.deliveryWindowQueries() > 0, "每日校正必须回查数据库");
        var full = locked(() -> facts.queryFactsWithoutCache(sample.supplierId(), today));
        assertEquals(400000L, full.getDueAmount());
        assertEquals(0, BigDecimal.ZERO.compareTo(full.getPenaltyAmountRaw()));
    }

    @Test
    void databaseRollbackAfterCacheIncrementShouldRetryWithoutDoubleAmount() {
        Map<String, Object> before = supplierRow();
        List<Map<String, Object>> products = productRows();
        context.getBean(SupplierScoreConcurrencyIT.FailureSwitch.class).failNextBatch.set(true);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> inbound(List.of(sample.orderIds().get(1))));
        assertTrue(error.getMessage().contains("测试批次号故障"));
        assertEquals(before, supplierRow());
        assertEquals(products, productRows());
        assertEquals(0, logCount());
        // Redis已去重登记，但数据库事务回滚；重投仍需要重新算写产品，不能把订单标记当成已完成评分。
        assertAmounts(28000000, 12000000, today);
        inbound(List.of(sample.orderIds().get(1)));
        assertExpectedCompletedScores();
        assertAmounts(28000000, 12000000, today);
        assertTrue(logCount() > 0);
    }

    @Test
    void pendingCleanupFailureAfterCommitShouldRetryWithoutDuplicatingScoresLogsOrAmounts() throws Exception {
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        PendingRedisSupport actual = new PendingRedisSupport(redis, json);
        actual.init();
        PendingRedisSupport controlled = spy(actual);
        AtomicBoolean failDelete = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (failDelete.compareAndSet(true, false)) throw new IllegalStateException("测试提交后pending清理故障");
            return actual.compareAndDelete(invocation.getArgument(0), invocation.getArgument(1));
        }).when(controlled).compareAndDelete(anyLong(), anyString());
        SupplierScoreFireConsumer consumer = new SupplierScoreFireConsumer(recalculate, controlled, context.getBean(RedissonClient.class));
        String token = context.getBean(com.qiheng.erp.common.util.BillNoGenerator.class)
                .nextNo("SC", context.getBean(com.qiheng.erp.purchase.mapper.SupplierScoreChangeLogMapper.class)::findMaxBatchNoSequence);
        assertTrue(token.matches("SC\\d{13}"));
        var pending = new ScoreRecalcPendingService.PendingSnapshot();
        pending.setBatchNo(token);
        pending.setSourceRefs(sample.orderIds().stream().map(id -> {
            var ref = new ScoreRecalcPendingService.PendingSnapshot.SourceRef();
            ref.setTriggerType(TriggerType.INBOUND_TRIGGER); ref.setSourceRefId(id);
            ref.setSourceRefNo(jdbc.queryForObject("SELECT purchase_no FROM purchase_order WHERE id=?", String.class, id));
            return ref;
        }).toList());
        redis.opsForValue().set(SupplierScoreRedisKeys.pendingKey(sample.supplierId()), json.writeValueAsString(pending), Duration.ofMinutes(6));
        SupplierScoreFireMessage message = new SupplierScoreFireMessage();
        message.setSupplierId(sample.supplierId()); message.setBatchNo(token);
        assertThrows(IllegalStateException.class, () -> consumer.onMessage(message));
        assertExpectedCompletedScores();
        assertTrue(Boolean.TRUE.equals(redis.hasKey(SupplierScoreRedisKeys.pendingKey(sample.supplierId()))));
        int logs = logCount();
        for (String sourcesJson : jdbc.queryForList("SELECT related_sources FROM supplier_score_change_log WHERE supplier_id=?",
                String.class, sample.supplierId())) {
            var sources = json.readTree(sourcesJson);
            assertTrue(sources.isArray());
            assertEquals(sample.orderIds().size(), sources.size(), "事务提交后清理失败也必须完整保存多单来源");
            var ids = new HashSet<Long>();
            for (var source : sources) {
                assertEquals("PURCHASE_ORDER", source.get("businessType").asText());
                Long id = Long.valueOf(source.get("businessId").asText()); ids.add(id);
                assertEquals(jdbc.queryForObject("SELECT purchase_no FROM purchase_order WHERE id=?", String.class, id),
                        source.get("businessNo").asText());
            }
            assertEquals(new HashSet<>(sample.orderIds()), ids);
        }
        Map<String, Object> supplier = supplierRow();
        List<Map<String, Object>> products = productRows();
        consumer.onMessage(message);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(SupplierScoreRedisKeys.pendingKey(sample.supplierId()))));
        assertAmounts(28000000, 12000000, today);
        assertEquals(logs, logCount()); assertEquals(supplier, supplierRow()); assertEquals(products, productRows());
        consumer.onMessage(message);
        assertEquals(logs, logCount(), "已删除pending的重复消息应当直接跳过");
        // 新窗口接管后，旧消息不能重算或删除它；直接执行真实 Lua 也必须返回不匹配。
        String nextBatch = context.getBean(com.qiheng.erp.common.util.BillNoGenerator.class)
                .nextNo("SC", context.getBean(com.qiheng.erp.purchase.mapper.SupplierScoreChangeLogMapper.class)::findMaxBatchNoSequence);
        pending.setBatchNo(nextBatch);
        redis.opsForValue().set(SupplierScoreRedisKeys.pendingKey(sample.supplierId()), json.writeValueAsString(pending), Duration.ofDays(1));
        consumer.onMessage(message);
        assertEquals(logs, logCount());
        assertNull(actual.findMatchingSnapshot(sample.supplierId(), token));
        assertFalse(actual.compareAndDelete(sample.supplierId(), token));
        assertEquals(nextBatch, actual.findMatchingSnapshot(sample.supplierId(), nextBatch).getBatchNo());
        assertTrue(actual.compareAndDelete(sample.supplierId(), nextBatch));
    }

    @Test
    void changedBusinessDayShouldRebuildInsteadOfAppendingYesterdayOrderAgain() {
        LocalDate tomorrow = today.plusDays(1);
        var nextDay = locked(() -> facts.queryInboundFacts(sample.supplierId(), tomorrow, sample.orderIds()));
        assertTrue(nextDay.isFullQualityRebuild());
        assertAmounts(28000000, 12000000, tomorrow);
        var retry = locked(() -> facts.queryInboundFacts(sample.supplierId(), tomorrow, sample.orderIds()));
        assertTrue(retry.isFullQualityRebuild(), "旧日完成的订单不能作为新日增量");
        assertSameAmounts(nextDay, retry);
        assertAmounts(28000000, 12000000, tomorrow);
        assertNull(qualityCache.read(sample.supplierId(), today));
        assertCacheTtl();
    }

    @Test
    void invalidCompletedOrderShouldBeSkippedWithoutApplyingItsAmount() {
        jdbc.update("UPDATE inbound_bill_item SET qualified_qty=900,defective_qty=0 WHERE id=?", sample.inboundItemIds().get(2));
        redis.delete(SupplierScoreRedisKeys.qualityAmountKey(sample.supplierId()));
        var snapshot = locked(() -> facts.queryInboundFacts(sample.supplierId(), today, sample.orderIds()));
        assertEquals(1, snapshot.getSkippedQualityOrders());
        assertEquals(BigInteger.valueOf(18000000), snapshot.getQualifiedAmountRaw());
        assertEquals(BigInteger.valueOf(12000000), snapshot.getDefectiveAmountRaw());
        assertFalse(Boolean.TRUE.equals(redis.opsForHash().hasKey(SupplierScoreRedisKeys.qualityAmountKey(sample.supplierId()), "applied:" + sample.orderIds().get(1))));
        inbound(sample.orderIds());
        assertEquals(6000, supplierScore("quality_score"));
        assertEquals(8000, productScore(0, "quality_score"));
        assertEquals(5000, productScore(1, "quality_score"));
        assertAmounts(18000000, 12000000, today);
    }

    @Test
    void invalidWarmOrderShouldRebuildBothFactsInsteadOfReusingDeliveryWithInvalidSamples() {
        var oldDelivery = locked(() -> facts.queryFactsWithoutCache(sample.supplierId(), today));
        assertEquals(400000L, oldDelivery.getDueAmount());
        assertEquals(0, BigDecimal.valueOf(100000).compareTo(oldDelivery.getPenaltyAmountRaw()));
        tracker.clear();
        // 质量缓存保留；新单整单异常时，必须同时重新校对交付样本。
        jdbc.update("UPDATE inbound_bill_item SET qualified_qty=900,defective_qty=0 WHERE id=?", sample.inboundItemIds().get(2));
        inbound(List.of(sample.orderIds().get(1)));
        assertAmounts(18000000, 12000000, today);
        var corrected = locked(() -> facts.queryFactsWithoutCache(sample.supplierId(), today));
        long independentlyValidDue = jdbc.queryForObject("SELECT SUM(total_amount) FROM purchase_order_item WHERE purchase_order_id=?",
                Long.class, sample.orderIds().getFirst());
        assertEquals(300000L, independentlyValidDue);
        assertEquals(independentlyValidDue, corrected.getDueAmount());
        assertEquals(0, BigDecimal.valueOf(75000).compareTo(corrected.getPenaltyAmountRaw()));
        assertEquals(7500, supplierScore("delivery_score"), "异常订单排除后仍按真实有效交付事实计算");
        assertEquals(6000, supplierScore("quality_score"));
        assertEquals(8000, productScore(0, "quality_score"));
        assertEquals(5000, productScore(1, "quality_score"));
        assertFalse(Boolean.TRUE.equals(redis.opsForHash().hasKey(SupplierScoreRedisKeys.qualityAmountKey(sample.supplierId()), "applied:" + sample.orderIds().get(1))));
        var full = locked(() -> facts.queryFactsWithoutCache(sample.supplierId(), today));
        assertEquals(1, full.getSkippedQualityOrders()); assertEquals(1, full.getSkippedDeliveryOrders());
        assertEquals(independentlyValidDue, full.getDueAmount().longValue());
        assertEquals(0, corrected.getPenaltyAmountRaw().compareTo(full.getPenaltyAmountRaw()));
        assertTrue(tracker.deliveryWindowQueries() > 0, "暖缓存遇到整单异常必须重新核对交付样本");
    }

    @Test
    void dailyAndInboundShouldSerializeAndProduceSameFullFacts() throws Exception {
        CountDownLatch begin = new CountDownLatch(1);
        Future<?> a = workers.submit(() -> { await(begin); inbound(List.of(sample.orderIds().get(1))); });
        Future<?> b = workers.submit(() -> { await(begin); locked(() -> recalculate.recalcFactsForSupplier(dailyContext(), today)); });
        begin.countDown();
        a.get(20, TimeUnit.SECONDS); b.get(20, TimeUnit.SECONDS);
        assertExpectedCompletedScores();
        assertAmounts(28000000, 12000000, today);
        var full = locked(() -> facts.queryFactsWithoutCache(sample.supplierId(), today));
        assertEquals(BigInteger.valueOf(28000000), full.getQualifiedAmountRaw());
        assertEquals(BigInteger.valueOf(12000000), full.getDefectiveAmountRaw());
        assertEquals(List.of(10000L, 20000L), basisRows().stream().map(row -> ((Number) row.get("score_basis_amount")).longValue()).toList());
    }

    /** 完整订单集合独立于日志来源列表，多单不允许丢失为只有最后一单。 */
    private void inbound(List<Long> ids) {
        locked(() -> recalculate.recalcFactsForSupplier(ScoreRecalcContext.builder().supplierId(sample.supplierId())
                .triggerType(TriggerType.INBOUND_TRIGGER).completedOrderIds(ids).operatorType("SYSTEM")
                .operatorName("真实缓存增量测试").build(), today));
    }

    /** 每日校正不带订单增量集合。 */
    private ScoreRecalcContext dailyContext() {
        return ScoreRecalcContext.builder().supplierId(sample.supplierId()).triggerType(TriggerType.DAILY_TRIGGER)
                .operatorType("SYSTEM").operatorName("真实缓存每日校正测试").build();
    }

    /** 先Redis再进入真实事务代理；所有工作线程遵守与消费者相同的顺序。 */
    private <T> T locked(Supplier<T> operation) {
        var lock = context.getBean(RedissonClient.class).getLock(SupplierScoreRedisKeys.lockKey(sample.supplierId()));
        try {
            assertTrue(lock.tryLock(10, TimeUnit.SECONDS));
            try { return operation.get(); } finally { lock.unlock(); }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试等待评分锁被中断", e); }
    }

    /** 独立常量验收增量后的分值，避免使用被测计算器作测试判定。 */
    private void assertExpectedCompletedScores() {
        assertEquals(30000L, jdbc.queryForObject("SELECT score_basis_amount FROM supplier WHERE id=?", Long.class, sample.supplierId()), "评分重算不能覆盖供应商历史金额");
        assertEquals(7000, supplierScore("quality_score")); assertEquals(7500, supplierScore("delivery_score"));
        assertEquals(8889, supplierScore("price_score")); assertEquals(7817, supplierScore("overall_score"));
        assertEquals(9000, productScore(0, "quality_score")); assertEquals(10000, productScore(0, "price_score"));
        assertEquals(8750, productScore(0, "recommend_score")); assertEquals(5000, productScore(1, "quality_score"));
        assertEquals(8333, productScore(1, "price_score")); assertEquals(7050, productScore(1, "recommend_score"));
    }

    /** 缓存和独立数据期望逐项比对，防止同单重复金额。 */
    private void assertAmounts(long qualified, long defective, LocalDate date) {
        var cache = qualityCache.read(sample.supplierId(), date); assertNotNull(cache);
        assertEquals(BigInteger.valueOf(qualified), cache.qualifiedRaw()); assertEquals(BigInteger.valueOf(defective), cache.defectiveRaw());
    }

    /** 单次fixture内可用直接联表作测试Oracle，生产查询仍由真实Mapper分页与Java归并执行。 */
    private void assertIndependentQualityTotals(long qualified, long defective) {
        Map<String, Object> sum = jdbc.queryForMap("SELECT SUM(ibi.qualified_qty*ibi.unit_price) qualified,SUM(ibi.defective_qty*ibi.unit_price) defective "
                + "FROM purchase_order po JOIN inbound_bill ib ON ib.source_id=po.id AND ib.source_type='PURCHASE_ORDER' AND ib.status='CONFIRMED' AND ib.deleted=0 "
                + "JOIN inbound_bill_item ibi ON ibi.inbound_bill_id=ib.id "
                + "WHERE po.supplier_id=? AND po.status='INBOUND_DONE' AND po.deleted=0 AND po.fully_received_at>=? AND po.fully_received_at<?",
                sample.supplierId(), Timestamp.valueOf(today.minusDays(179).atStartOfDay()), Timestamp.valueOf(today.plusDays(1).atStartOfDay()));
        assertEquals(new BigDecimal(qualified), new BigDecimal(sum.get("qualified").toString()));
        assertEquals(new BigDecimal(defective), new BigDecimal(sum.get("defective").toString()));
    }

    /** 本夹具每条采购明细只有一个全量确认批次，独立SQL核对金额而不是仅核对相同交付比值。 */
    private void assertIndependentDeliveryTotals() {
        Map<String, Object> sum = jdbc.queryForMap("SELECT SUM(poi.total_amount) due, "
                + "SUM(poi.total_amount*ibi.current_qty/poi.quantity*CASE "
                + "WHEN DATEDIFF(DATE(ib.confirmed_at),po.expected_arrival_date)<=0 THEN 0 "
                + "WHEN DATEDIFF(DATE(ib.confirmed_at),po.expected_arrival_date)<=3 THEN 0.25 "
                + "WHEN DATEDIFF(DATE(ib.confirmed_at),po.expected_arrival_date)<=7 THEN 0.5 "
                + "WHEN DATEDIFF(DATE(ib.confirmed_at),po.expected_arrival_date)<=15 THEN 0.75 ELSE 1 END) penalty "
                + "FROM purchase_order po JOIN purchase_order_item poi ON poi.purchase_order_id=po.id "
                + "JOIN inbound_bill ib ON ib.source_id=po.id AND ib.source_type='PURCHASE_ORDER' AND ib.status='CONFIRMED' AND ib.deleted=0 "
                + "JOIN inbound_bill_item ibi ON ibi.inbound_bill_id=ib.id AND ibi.source_item_id=poi.id "
                + "WHERE po.supplier_id=? AND po.deleted=0 AND po.status IN ('APPROVED','PARTIAL_INBOUND','INBOUND_DONE') "
                + "AND po.expected_arrival_date>=? AND po.expected_arrival_date<?", sample.supplierId(), today.minusDays(180), today);
        var actual = locked(() -> facts.queryFactsWithoutCache(sample.supplierId(), today));
        assertEquals(new BigDecimal(sum.get("due").toString()).longValueExact(), actual.getDueAmount());
        assertEquals(0, new BigDecimal(sum.get("penalty").toString()).compareTo(actual.getPenaltyAmountRaw()));
    }

    /** 独立事实与缓存快照校验原始罚额，不使用展示罚额掩盖亚分差异。 */
    private void assertSameAmounts(FactsSnapshot a, FactsSnapshot b) {
        assertEquals(a.getQualifiedAmountRaw(), b.getQualifiedAmountRaw()); assertEquals(a.getDefectiveAmountRaw(), b.getDefectiveAmountRaw());
        assertEquals(a.getDueAmount(), b.getDueAmount()); assertEquals(0, a.getPenaltyAmountRaw().compareTo(b.getPenaltyAmountRaw()));
    }

    /** 对经过Java/MyBatis真正执行的产品过滤SQL使用同一参数EXPLAIN，不手抄另一条SQL。 */
    private void explainActualFilteredSql() {
        QueryEvent event = tracker.events.stream().filter(e -> e.id().endsWith("selectQualityOrdersForProducts")
                && e.sql().contains("fully_received_at >=")).findFirst().orElseThrow();
        List<Map<String, Object>> plan = jdbc.queryForList("EXPLAIN " + event.sql(), event.parameters().toArray());
        assertFalse(plan.isEmpty());
        assertTrue(plan.stream().anyMatch(row -> row.get("key") != null), "实际产品过滤查询至少应使用关联或窗口索引");
        System.out.println("QUALITY_PRODUCT_EXPLAIN " + plan.stream().map(row -> Map.of("table", Objects.toString(row.get("table"), ""),
                "key", Objects.toString(row.get("key"), ""), "rows", Objects.toString(row.get("rows"), ""))).toList());
    }

    /** 质量缓存仍应有24小时TTL，不允许创建永久键。 */
    private void assertCacheTtl() {
        for (String key : List.of(SupplierScoreRedisKeys.qualityAmountKey(sample.supplierId()))) {
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS); assertNotNull(ttl); assertTrue(ttl > 85000 && ttl <= 86400);
        }
    }

    private int supplierScore(String column) { return jdbc.queryForObject("SELECT " + column + " FROM supplier WHERE id=?", Integer.class, sample.supplierId()); }
    private int productScore(int index, String column) { return jdbc.queryForObject("SELECT " + column + " FROM supplier_product WHERE id=?", Integer.class, sample.supplierProductIds().get(index)); }
    private Map<String, Object> supplierRow() { return jdbc.queryForMap("SELECT quality_score,delivery_score,price_score,overall_score,version FROM supplier WHERE id=?", sample.supplierId()); }
    private Map<String, Object> productRow(int index) { return jdbc.queryForMap("SELECT quality_score,price_score,recommend_score,version FROM supplier_product WHERE id=?", sample.supplierProductIds().get(index)); }
    private List<Map<String, Object>> productRows() { return jdbc.queryForList("SELECT id,quality_score,price_score,recommend_score,version FROM supplier_product WHERE supplier_id=? ORDER BY id", sample.supplierId()); }
    private List<Map<String, Object>> basisRows() { return jdbc.queryForList("SELECT id,score_basis_amount FROM supplier_product WHERE supplier_id=? ORDER BY id", sample.supplierId()); }
    private int logCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?", Integer.class, sample.supplierId()); }

    /** 工作线程使用有界启动闸门，不依靠sleep碰运气。 */
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("测试启动被中断", e); }
    }

    /** 收集Mapper实际BoundSql与完整参数，只用于本测试SQL对账和EXPLAIN。 */
    record QueryEvent(String id, String sql, List<Object> parameters) { }

    @Intercepts(@Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}))
    static class QueryTracker implements Interceptor {
        final List<QueryEvent> events = new CopyOnWriteArrayList<>();
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            MappedStatement mapped = (MappedStatement) invocation.getArgs()[0];
            Object parameter = invocation.getArgs()[1];
            BoundSql bound = mapped.getBoundSql(parameter);
            List<Object> values = new ArrayList<>();
            for (var mapping : bound.getParameterMappings()) {
                String property = mapping.getProperty();
                if (bound.hasAdditionalParameter(property)) values.add(bound.getAdditionalParameter(property));
                else if (parameter == null) values.add(null);
                else if (mapped.getConfiguration().getTypeHandlerRegistry().hasTypeHandler(parameter.getClass())) values.add(parameter);
                else values.add(mapped.getConfiguration().newMetaObject(parameter).getValue(property));
            }
            events.add(new QueryEvent(mapped.getId(), bound.getSql().replaceAll("\\s+", " ").trim(), values));
            return invocation.proceed();
        }
        long deliveryWindowQueries() {
            return events.stream().filter(e -> e.id().contains("PurchaseOrderMapper")
                    && e.sql().contains("expected_arrival_date >=")).count();
        }
        void clear() { events.clear(); }
    }

    /** 在SqlSessionFactory完成后注入观察插件，不改变Mapper、事务或缓存行为。 */
    static class QueryTrackingPostProcessor implements BeanPostProcessor {
        final QueryTracker tracker = new QueryTracker();
        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof SqlSessionFactory factory) factory.getConfiguration().addInterceptor(tracker);
            return bean;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(SupplierScoreConcurrencyIT.RealConfiguration.class)
    static class RealConfiguration {
        @Bean static QueryTrackingPostProcessor queryTrackingPostProcessor() { return new QueryTrackingPostProcessor(); }
    }
}
