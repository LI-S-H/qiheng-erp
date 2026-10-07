package com.qiheng.erp.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.redisson.api.RedissonClient;
import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreRecalcContext;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 启动真实 Tomcat，通过登录和业务 HTTP 接口验证评分日志；数据源使用开发配置的真实 MySQL/Redis。
 * 仅在 -Dscore.log.api.it=true 时执行，默认结束后按唯一夹具主键精确清理。
 * 开发库审阅可显式启用 -Dscore.log.api.it.keep-data=true，只有全部断言成功才保留业务数据；失败仍清理。
 * 禁止调度和正式消费者运行，避免修改已有业务评分或消费正式消息。
 */
@EnabledIfSystemProperty(named = "score.log.api.it", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "supplier-score.scheduled.enabled=false",
        "supplier-score.rocketmq.consumer.enabled=false",
        "sa-token.is-log=false",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl",
        "rocketmq.producer.group=score-log-http-it-producer",
        "supplier-score.rocketmq.producer.group=score-log-http-it-score-producer"
})
class SupplierScoreLogApiIT {
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    @Autowired private SupplierScoreRecalculateService recalculateService;
    @Autowired private RedissonClient redisson;
    @Autowired private StringRedisTemplate redis;
    @LocalServerPort private int port;
    private final String fixture = "评分日志HTTP测试-" + UUID.randomUUID();
    private String tokenName;
    private String token;
    private Long supplierId;
    private Long productId;
    private Long spId;
    private String supplierCode;
    private String productCode;
    private String actorId;
    private String actorName;

    @Test
    void realHttpUserScenariosMustPersistCorrectSourcesIdentityReasonAndScores() throws Exception {
        boolean verified = false;
        try {
            // 凭据可通过环境变量覆盖；任何断言、日志都不输出密码或 Token。
            JsonNode login = request(HttpMethod.POST, "/auth/login", Map.of(
                    "username", System.getenv().getOrDefault("ERP_IT_LOGIN_USER", "admin"),
                    "password", System.getenv().getOrDefault("ERP_IT_LOGIN_PASSWORD", "123456")));
            tokenName = login.path("tokenName").asText();
            token = login.path("token").asText();
            assertFalse(token.isBlank());
            actorId = login.path("user").path("userId").asText();
            actorName = login.path("user").path("realName").asText();

            JsonNode supplier = request(HttpMethod.POST, "/purchase/suppliers", Map.of(
                    "supplierName", fixture, "status", 1, "remark", fixture));
            supplierId = supplier.path("supplierId").asLong();
            supplierCode = supplier.path("supplierCode").asText();
            assertTrue(supplierId > 0);
            JsonNode product = request(HttpMethod.POST, "/products", Map.ofEntries(
                    Map.entry("productName", fixture), Map.entry("brandName", ""),
                    Map.entry("categoryId", jdbc.queryForObject("SELECT id FROM product_category WHERE status=1 AND deleted=0 ORDER BY id LIMIT 1", Long.class).toString()),
                    Map.entry("unitName", "件"), Map.entry("quantityPrecision", 0),
                    Map.entry("specification", ""), Map.entry("referencePurchasePrice", "10.00"),
                    Map.entry("referenceSalePrice", "20.00"), Map.entry("safetyStockQty", 0),
                    Map.entry("status", 1), Map.entry("remark", fixture)));
            productId = product.path("productId").asLong();
            productCode = product.path("productCode").asText();
            assertTrue(productId > 0);
            JsonNode sp = request(HttpMethod.POST, "/purchase/supplier-products", Map.of(
                    "supplierId", supplierId.toString(), "productId", productId.toString(),
                    "status", 1, "minOrderQty", 1, "remark", fixture));
            spId = sp.path("supplierProductId").asLong();
            assertTrue(spId > 0);
            // 只为新建夹具设置已审核的基础事实，之后全部评分变化通过真实业务 HTTP 入口执行。
            assertEquals(1, jdbc.update("UPDATE supplier SET quality_score=8000,delivery_score=9000,score_basis_amount=10000 WHERE id=? AND remark=?", supplierId, fixture));
            assertEquals(1, jdbc.update("UPDATE supplier_product SET quality_score=8000,score_basis_amount=10000 WHERE id=? AND remark=?", spId, fixture));

            request(HttpMethod.PUT, "/purchase/suppliers/" + supplierId + "/service-score", Map.of(
                    "version", version("supplier", supplierId), "serviceScore", 85, "reason", "HTTP人工服务分调整"));
            assertLogs("SERVICE_TRIGGER", "SUPPLIER", supplierId, supplierCode, "HTTP人工服务分调整");
            assertEquals(8500, jdbc.queryForObject("SELECT service_score FROM supplier WHERE id=?", Integer.class, supplierId));

            request(HttpMethod.PUT, "/purchase/supplier-products/" + spId + "/quote", Map.of(
                    "version", version("supplier_product", spId), "quotedPurchasePrice", "12.00",
                    "quoteValidUntil", LocalDate.now().plusDays(5).toString(), "reason", "HTTP报价调整"));
            assertLatestPriceLogs("SUPPLIER_PRODUCT", spId, null, "HTTP报价调整");
            assertScores(8333, 8450);

            // 真实 HTTP 参考价入口被供应商行锁阻塞超过原默认30秒，验证外层产品锁由watchdog续约。
            var globalLock = redisson.getLock("product:category:global");
            try (var blocker = jdbc.getDataSource().getConnection();
                 var executor = Executors.newSingleThreadExecutor()) {
                blocker.setAutoCommit(false);
                try (var statement = blocker.prepareStatement("SELECT id FROM supplier WHERE id=? AND remark=? FOR UPDATE")) {
                    statement.setLong(1, supplierId);
                    statement.setString(2, fixture);
                    try (var row = statement.executeQuery()) { assertTrue(row.next()); }
                }
                var blockedReference = executor.submit(() -> request(HttpMethod.PUT,
                        "/products/" + productId + "/reference-price", Map.of("referencePurchasePrice", "9.00")));
                long lockWaitDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                try {
                    while (!globalLock.isLocked() && System.nanoTime() < lockWaitDeadline) Thread.sleep(50);
                    assertTrue(globalLock.isLocked(), "HTTP参考价入口必须取得真实产品全局锁");
                    assertFalse(blockedReference.isDone(), "新供应商行锁应阻塞参考价评分事务");
                    long started = System.nanoTime();
                    Thread.sleep(35000);
                    long blockedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                    assertFalse(blockedReference.isDone(), "事务等待期间不能提前返回或提交");
                    assertTrue(globalLock.isLocked(), "超过30秒后产品锁仍应由watchdog续约");
                    long remainingTtl = globalLock.remainTimeToLive();
                    assertTrue(remainingTtl > 0, "续约锁仍必须有正TTL");
                    boolean competitorAcquired = globalLock.tryLock(0, TimeUnit.SECONDS);
                    if (competitorAcquired) globalLock.unlock();
                    assertFalse(competitorAcquired, "数据库事务未提交时其他线程不能获得产品锁");
                    System.out.println("HTTP_REFERENCE_WATCHDOG_VERIFIED blockedMillis=" + blockedMillis
                            + " remainingTtl=" + remainingTtl + " competitorAcquired=false supplierId=" + supplierId);
                } finally {
                    // 先释放独立事务行锁，再等待HTTP线程结束；失败也不能留下阻塞真实后端的测试事务。
                    blocker.rollback();
                }
                blockedReference.get(45, TimeUnit.SECONDS);
            }
            boolean successReleased = globalLock.tryLock(0, TimeUnit.SECONDS);
            try { assertTrue(successReleased, "HTTP事务成功返回后外层产品锁必须释放"); }
            finally { if (successReleased) globalLock.unlock(); }
            assertLatestPriceLogs("PRODUCT", productId, productCode, "产品参考采购价调整");
            assertScores(7500, 8200);

            // 不存在产品会在实际Service和锁切面内失败；错误返回后锁仍必须释放，原夹具没有副作用。
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=?", Integer.class, Long.MAX_VALUE));
            HttpHeaders failedReferenceHeaders = new HttpHeaders();
            failedReferenceHeaders.setContentType(MediaType.APPLICATION_JSON);
            failedReferenceHeaders.set(tokenName, token);
            var failedReference = http.exchange("/products/" + Long.MAX_VALUE + "/reference-price", HttpMethod.PUT,
                    new HttpEntity<>(Map.of("referencePurchasePrice", "9.00"), failedReferenceHeaders), String.class);
            assertNotEquals(0, mapper.readTree(failedReference.getBody()).path("code").asInt());
            boolean errorReleased = globalLock.tryLock(0, TimeUnit.SECONDS);
            try { assertTrue(errorReleased, "Service异常返回后外层产品锁必须释放"); }
            finally { if (errorReleased) globalLock.unlock(); }
            assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?", Integer.class, supplierId));
            assertScores(7500, 8200);
            System.out.println("HTTP_REFERENCE_LOCK_RELEASE_VERIFIED successAfterCommit=true exceptionReleased=true");

            Map<String, Object> clearQuote = new HashMap<>();
            clearQuote.put("version", version("supplier_product", spId));
            clearQuote.put("quotedPurchasePrice", null);
            clearQuote.put("quoteValidUntil", null);
            clearQuote.put("reason", "HTTP清空报价原因必须保留");
            request(HttpMethod.PUT, "/purchase/supplier-products/" + spId + "/quote", clearQuote);
            assertLatestPriceLogs("SUPPLIER_PRODUCT", spId, null, "HTTP清空报价原因必须保留");
            assertScores(null, null);
            assertNull(jdbc.queryForObject("SELECT quoted_purchase_price FROM supplier_product WHERE id=?", Long.class, spId));

            // 乐观锁失败不能留下报价、分数或日志的部分更新。
            int currentVersion = version("supplier_product", spId);
            HttpHeaders staleHeaders = new HttpHeaders();
            staleHeaders.setContentType(MediaType.APPLICATION_JSON);
            staleHeaders.set(tokenName, token);
            var staleResponse = http.exchange("/purchase/supplier-products/" + spId + "/quote", HttpMethod.PUT,
                    new HttpEntity<>(Map.of("version", currentVersion - 1, "quotedPurchasePrice", "11.00",
                            "quoteValidUntil", LocalDate.now().plusDays(5).toString(), "reason", "HTTP旧版本必须拒绝"), staleHeaders), String.class);
            assertNotEquals(0, mapper.readTree(staleResponse.getBody()).path("code").asInt());
            assertEquals(currentVersion, version("supplier_product", spId));
            assertScores(null, null);

            JsonNode logs = request(HttpMethod.GET, "/purchase/score-change-logs?supplierId=" + supplierId + "&pageSize=100", null);
            assertEquals(7, logs.path("total").asInt());
            assertEquals(7, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?", Integer.class, supplierId));
            for (JsonNode record : logs.path("records")) {
                assertTrue(record.path("relatedSources").isArray());
                assertEquals(1, record.path("relatedSources").size());
                assertTrue(record.path("relatedSources").get(0).path("businessId").isTextual());
                assertFalse(record.has("relatedBusinessId"));
                assertFalse(record.has("relatedBusinessNo"));
                assertEquals(actorId, record.path("operatorId").asText());
                for (String field : java.util.List.of("metricScoreBefore", "metricScoreAfter",
                        "productRecommendScoreBefore", "productRecommendScoreAfter",
                        "supplierOverallScoreBefore", "supplierOverallScoreAfter")) {
                    assertTrue(record.has(field), "空分数必须明确返回null: " + field);
                }
                assertTrue(record.path("relatedSources").get(0).has("businessNo"));
            }
            System.out.println("HTTP_SCORE_LOG_VERIFIED scenarios=service,quote,reference,clear-quote,stale-version,query logs=7 realTomcat=true realMysql=true");
            System.out.println("HTTP_SCORE_LOG_SERVER_STARTED actualTomcatPort=" + port);

            // 只调本夹具供应商的真实系统入口，不执行全局定时任务。
            request(HttpMethod.PUT, "/purchase/supplier-products/" + spId + "/quote", Map.of(
                    "version", version("supplier_product", spId), "quotedPurchasePrice", "10.00",
                    "quoteValidUntil", LocalDate.now().plusDays(5).toString(), "reason", "HTTP准备报价到期夹具"));
            String longReason = "因".repeat(500);
            request(HttpMethod.PUT, "/purchase/suppliers/" + supplierId + "/service-score", Map.of(
                    "version", version("supplier", supplierId), "serviceScore", 86, "reason", longReason));
            String serviceBatch = jdbc.queryForObject("SELECT batch_no FROM supplier_score_change_log WHERE supplier_id=? AND trigger_type='SERVICE_TRIGGER' ORDER BY id DESC LIMIT 1", String.class, supplierId);
            var longReasonRows = jdbc.queryForList("SELECT * FROM supplier_score_change_log WHERE supplier_id=? AND batch_no=?", supplierId, serviceBatch);
            assertEquals(2, longReasonRows.size());
            for (var row : longReasonRows) {
                String savedReason = row.get("reason").toString();
                assertTrue(savedReason.startsWith(longReason));
                if (row.get("supplier_product_id") == null) assertEquals(longReason, savedReason);
                else {
                    assertTrue(savedReason.length() > 500 && savedReason.length() <= 600);
                    assertTrue(savedReason.endsWith("仅供货产品推荐分校正"));
                }
                System.out.println("DB_SCORE_LOG_REASON_BOUNDARY batch=" + serviceBatch + " reasonLength=" + savedReason.length()
                        + " originalPreserved=true productDerived=" + (row.get("supplier_product_id") != null));
            }
            assertScores(9000, 8660);
            assertEquals(1, jdbc.update("UPDATE supplier_product SET quote_valid_until=? WHERE id=? AND remark=?",
                    LocalDate.now().minusDays(1), spId, fixture));
            var lock = redisson.getLock(SupplierScoreRedisKeys.lockKey(supplierId));
            lock.lock();
            try { recalculateService.recalcPricesForSupplier(supplierId); }
            finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
            var expiryRows = jdbc.queryForList("SELECT * FROM supplier_score_change_log WHERE supplier_id=? AND trigger_type='QUOTE_EXPIRED_TRIGGER'", supplierId);
            assertEquals(2, expiryRows.size());
            for (var row : expiryRows) {
                assertEquals("SYSTEM", row.get("operator_type"));
                assertNull(row.get("operator_id"));
                assertEquals("报价到期扫描", row.get("operator_name"));
                JsonNode sources = mapper.readTree(row.get("related_sources").toString());
                assertEquals(1, sources.size());
                assertEquals("SUPPLIER_PRODUCT", sources.get(0).path("businessType").asText());
                assertEquals(spId.toString(), sources.get(0).path("businessId").asText());
                System.out.println("DB_SCORE_LOG_SYSTEM trigger=" + row.get("trigger_type") + " batch=" + row.get("batch_no")
                        + " sources=" + row.get("related_sources") + " operator=" + row.get("operator_type") + " name=" + row.get("operator_name"));
            }
            assertScores(null, null);

            LocalDate date = LocalDate.now();
            lock.lock();
            try {
                recalculateService.recalcFactsForSupplier(ScoreRecalcContext.builder().supplierId(supplierId)
                        .triggerType(TriggerType.DAILY_TRIGGER).relatedSources(java.util.List.of())
                        .operatorType("SYSTEM").operatorName("每日事实校正")
                        .mergedReason("每日事实校正，业务日期=" + date).executionMode("SCHEDULED").build(), date);
            } finally { if (lock.isHeldByCurrentThread()) lock.unlock(); }
            var dailyRows = jdbc.queryForList("SELECT * FROM supplier_score_change_log WHERE supplier_id=? AND trigger_type='DAILY_TRIGGER'", supplierId);
            assertEquals(3, dailyRows.size());
            for (var row : dailyRows) {
                assertEquals("SYSTEM", row.get("operator_type"));
                assertNull(row.get("operator_id"));
                assertEquals("每日事实校正", row.get("operator_name"));
                assertEquals(0, mapper.readTree(row.get("related_sources").toString()).size());
                assertEquals("每日事实校正，业务日期=" + date, row.get("reason"));
                System.out.println("DB_SCORE_LOG_SYSTEM trigger=" + row.get("trigger_type") + " batch=" + row.get("batch_no")
                        + " sources=" + row.get("related_sources") + " operator=" + row.get("operator_type") + " reason=" + row.get("reason"));
            }
            assertNull(jdbc.queryForObject("SELECT quality_score FROM supplier WHERE id=?", Integer.class, supplierId));
            assertNull(jdbc.queryForObject("SELECT delivery_score FROM supplier WHERE id=?", Integer.class, supplierId));
            assertNull(jdbc.queryForObject("SELECT quality_score FROM supplier_product WHERE id=?", Integer.class, spId));
            System.out.println("SYSTEM_SCORE_LOG_VERIFIED scenarios=quote-expired,daily logs=2,3 realBean=true realMysql=true");
            JsonNode allLogs = request(HttpMethod.GET, "/purchase/score-change-logs?supplierId=" + supplierId + "&pageSize=100", null);
            assertEquals(16, allLogs.path("total").asInt());
            for (JsonNode record : allLogs.path("records")) {
                assertTrue(record.has("operatorId"));
                if ("SYSTEM".equals(record.path("operatorType").asText())) assertTrue(record.path("operatorId").isNull());
                for (String field : java.util.List.of("metricScoreBefore", "metricScoreAfter",
                        "productRecommendScoreBefore", "productRecommendScoreAfter",
                        "supplierOverallScoreBefore", "supplierOverallScoreAfter")) assertTrue(record.has(field));
                for (JsonNode source : record.path("relatedSources")) {
                    assertTrue(source.has("businessNo"));
                    if ("SUPPLIER_PRODUCT".equals(source.path("businessType").asText())) assertTrue(source.path("businessNo").isNull());
                }
            }
            System.out.println("HTTP_SCORE_LOG_NULL_CONTRACT_VERIFIED records=16 allNullableFieldsPresent=true systemOperatorIdNull=true sourceBusinessNoNull=true");
            verified = true;
        } finally {
            try {
                // 保留业务日志不等于保留认证会话；注销失败时也不保留本轮夹具。
                if (token != null) request(HttpMethod.POST, "/auth/logout", null);
                if (verified && Boolean.getBoolean("score.log.api.it.keep-data")) {
                    System.out.println("HTTP_SCORE_LOG_FIXTURE_RETAINED fixture=" + fixture
                            + " supplierId=" + supplierId + " supplierCode=" + supplierCode + " supplierName=" + fixture
                            + " productId=" + productId + " productCode=" + productCode + " supplierProductId=" + spId
                            + " logCount=" + jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?", Integer.class, supplierId)
                            + " batchNos=" + jdbc.queryForList("SELECT DISTINCT batch_no FROM supplier_score_change_log WHERE supplier_id=? ORDER BY batch_no", String.class, supplierId));
                } else {
                    cleanup();
                }
            } catch (Exception | AssertionError failure) {
                cleanup();
                throw failure;
            }
        }
    }

    /** 真实 HTTP 请求必须同时通过 HTTP 状态和业务状态校验；失败信息不包含敏感响应。 */
    private JsonNode request(HttpMethod method, String path, Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.set(tokenName, token);
        var response = http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        assertEquals(200, response.getStatusCode().value(), "HTTP请求失败: " + path);
        JsonNode root = mapper.readTree(response.getBody());
        assertEquals(0, root.path("code").asInt(), "业务请求失败: " + path + "，" + root.path("message").asText());
        return root.path("data");
    }

    /** 版本读取只允许本测试指定的三张业务表。 */
    private int version(String table, long id) {
        assertTrue(java.util.Set.of("supplier", "product", "supplier_product").contains(table));
        return jdbc.queryForObject("SELECT version FROM " + table + " WHERE id=?", Integer.class, id);
    }

    /** 人工日志来源和用户姓名必须与真实登录响应一致。 */
    private void assertLogs(String trigger, String type, Long id, String no, String reason) throws Exception {
        var rows = jdbc.queryForList("SELECT * FROM supplier_score_change_log WHERE supplier_id=? AND trigger_type=? ORDER BY id", supplierId, trigger);
        assertFalse(rows.isEmpty());
        for (var row : rows) assertLog(row, type, id, no, reason);
    }

    /** 只检查最新价格批次，避免把前一次报价来源与参考价来源混在一起。 */
    private void assertLatestPriceLogs(String type, Long id, String no, String reason) throws Exception {
        String batch = jdbc.queryForObject("SELECT batch_no FROM supplier_score_change_log WHERE supplier_id=? AND trigger_type='PRICE_TRIGGER' ORDER BY id DESC LIMIT 1", String.class, supplierId);
        var rows = jdbc.queryForList("SELECT * FROM supplier_score_change_log WHERE supplier_id=? AND batch_no=?", supplierId, batch);
        assertEquals(2, rows.size());
        for (var row : rows) assertLog(row, type, id, no, reason);
    }

    /** 从实际 JSON 列核对类型、ID、编号、原因和人工身份，不能只看接口响应。 */
    private void assertLog(Map<String, Object> row, String type, Long id, String no, String reason) throws Exception {
        JsonNode sources = mapper.readTree(row.get("related_sources").toString());
        assertEquals(1, sources.size());
        assertEquals(type, sources.get(0).path("businessType").asText());
        assertEquals(id.toString(), sources.get(0).path("businessId").asText());
        if (no == null) {
            assertTrue(sources.get(0).has("businessNo"));
            assertTrue(sources.get(0).path("businessNo").isNull());
        }
        else assertEquals(no, sources.get(0).path("businessNo").asText());
        assertEquals("USER", row.get("operator_type"));
        assertEquals(actorId, row.get("operator_id").toString());
        assertEquals(actorName, row.get("operator_name"));
        assertEquals(reason, row.get("reason"));
        assertTrue(row.get("batch_no").toString().matches("SC[0-9]{13}"));
        System.out.println("DB_SCORE_LOG_USER trigger=" + row.get("trigger_type") + " batch=" + row.get("batch_no")
                + " sources=" + row.get("related_sources") + " operator=" + row.get("operator_type") + " name=" + row.get("operator_name")
                + " metricBefore=" + row.get("metric_score_before") + " metricAfter=" + row.get("metric_score_after") + " reason=" + row.get("reason"));
    }

    /** 独立固定预期值核对价格、综合与推荐分；报价清空后必须真正写 NULL。 */
    private void assertScores(Integer price, Integer overall) {
        assertEquals(price, jdbc.queryForObject("SELECT price_score FROM supplier_product WHERE id=?", Integer.class, spId));
        assertEquals(price, jdbc.queryForObject("SELECT price_score FROM supplier WHERE id=?", Integer.class, supplierId));
        assertEquals(overall, jdbc.queryForObject("SELECT recommend_score FROM supplier_product WHERE id=?", Integer.class, spId));
        assertEquals(overall, jdbc.queryForObject("SELECT overall_score FROM supplier WHERE id=?", Integer.class, supplierId));
    }

    /** 精确备注只用于恢复本测试创建的主键；删除始终使用验证过的具体 ID，不碰现有业务。 */
    private void cleanup() {
        for (Long ownedSupplier : jdbc.queryForList("SELECT id FROM supplier WHERE remark=?", Long.class, fixture)) {
            jdbc.update("DELETE FROM supplier_score_change_log WHERE supplier_id=?", ownedSupplier);
            for (Long ownedSp : jdbc.queryForList("SELECT id FROM supplier_product WHERE supplier_id=? AND remark=?", Long.class, ownedSupplier, fixture)) {
                jdbc.update("DELETE FROM supplier_product WHERE id=? AND remark=?", ownedSp, fixture);
            }
            jdbc.update("DELETE FROM supplier WHERE id=? AND remark=?", ownedSupplier, fixture);
            redis.delete(SupplierScoreRedisKeys.qualityAmountKey(ownedSupplier));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?", Integer.class, ownedSupplier));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_product WHERE supplier_id=?", Integer.class, ownedSupplier));
        }
        for (Long ownedProduct : jdbc.queryForList("SELECT id FROM product WHERE remark=?", Long.class, fixture)) {
            jdbc.update("DELETE FROM product WHERE id=? AND remark=?", ownedProduct, fixture);
        }
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM supplier WHERE remark=?", Integer.class, fixture));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE remark=?", Integer.class, fixture));
        System.out.println("HTTP_SCORE_LOG_FIXTURE_CLEANED exactOwnedIds=true");
    }
}
