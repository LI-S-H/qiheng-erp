package com.qiheng.erp.admin;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实开发后端验证审核评分快照和库存联表查询；只有显式启用才创建数据。
 * 夹具全部通过唯一备注定位并按确切ID清理，禁止正式MQ和评分定时任务启动。
 */
@EnabledIfSystemProperty(named = "purchase.stock.http.it", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rocketmq.name-server=false",
        "spring.autoconfigure.exclude=org.apache.rocketmq.spring.autoconfigure.RocketMQAutoConfiguration",
        "supplier-score.rocketmq.consumer.enabled=false",
        "supplier-score.scheduled.enabled=false",
        "sa-token.is-log=false",
        "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
})
class PurchaseSnapshotStockHttpIT {
    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper mapper;
    private final String fixture = "采购库存HTTP测试-" + UUID.randomUUID();
    private final List<Long> products = new ArrayList<>();
    private final List<String> productCodes = new ArrayList<>();
    private final List<Long> relationships = new ArrayList<>();
    private final List<Long> warehouses = new ArrayList<>();
    private String tokenName;
    private String token;
    private Long supplierId;

    @Test
    void actualHttpMustFreezeApprovalScoresAndQueryCorrectStockBoundaries() throws Exception {
        try {
            JsonNode login = request(HttpMethod.POST, "/auth/login", Map.of(
                    "username", System.getenv().getOrDefault("ERP_IT_LOGIN_USER", "admin"),
                    "password", System.getenv().getOrDefault("ERP_IT_LOGIN_PASSWORD", "123456")));
            tokenName = login.path("tokenName").asText();
            token = login.path("token").asText();
            assertFalse(token.isBlank());
            supplierId = request(HttpMethod.POST, "/purchase/suppliers", Map.of(
                    "supplierName", fixture, "status", 1, "remark", fixture)).path("supplierId").asLong();
            assertTrue(supplierId > 0);
            Long categoryId = jdbc.queryForObject("SELECT id FROM product_category WHERE status=1 AND deleted=0 ORDER BY id LIMIT 1", Long.class);
            for (int index = 0; index < 5; index++) {
                JsonNode product = request(HttpMethod.POST, "/products", Map.ofEntries(
                        Map.entry("productName", fixture + "-" + index), Map.entry("categoryId", categoryId.toString()),
                        Map.entry("brandName", ""), Map.entry("unitName", "件"), Map.entry("quantityPrecision", 2),
                        Map.entry("specification", ""), Map.entry("referencePurchasePrice", "10.00"),
                        Map.entry("referenceSalePrice", "20.00"), Map.entry("safetyStockQty", index == 3 ? 0 : 1.25),
                        Map.entry("status", 1), Map.entry("remark", fixture)));
                products.add(product.path("productId").asLong());
                productCodes.add(product.path("productCode").asText());
                if (index < 3) relationships.add(request(HttpMethod.POST, "/purchase/supplier-products", Map.of(
                        "supplierId", supplierId.toString(), "productId", products.get(index).toString(),
                        "status", 1, "minOrderQty", 1, "remark", fixture)).path("supplierProductId").asLong());
            }
            for (int index = 0; index < 2; index++) warehouses.add(request(HttpMethod.POST, "/warehouse/warehouses", Map.of(
                    "warehouseName", fixture + "-" + index, "contactName", "测试", "contactPhone", "13800000000", "address", "隔离测试",
                    "status", 1, "remark", fixture)).path("warehouseId").asLong());
            // 只设置独立夹具推荐分，不把SQL预置冒充真实算分；审核快照本身必须走正式HTTP事务。
            jdbc.update("UPDATE supplier_product SET score_status='READY',recommend_score=8123 WHERE id=? AND remark=?", relationships.get(0), fixture);
            jdbc.update("UPDATE supplier_product SET score_status='READY',recommend_score=0 WHERE id=? AND remark=?", relationships.get(1), fixture);
            jdbc.update("UPDATE supplier_product SET score_status='NOT_READY',recommend_score=NULL WHERE id=? AND remark=?", relationships.get(2), fixture);
            Long orderId = createOrder(3);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM purchase_order_item WHERE purchase_order_id=? AND selected_supplier_score IS NOT NULL", Integer.class, orderId));
            assertSnapshots(orderId, Arrays.asList(null, null, null));
            request(HttpMethod.POST, "/purchase/orders/" + orderId + "/submit", Map.of("version", orderVersion(orderId)));
            int submittedVersion = orderVersion(orderId);
            request(HttpMethod.POST, "/purchase/orders/" + orderId + "/approve", Map.of("version", submittedVersion));
            assertSnapshots(orderId, Arrays.asList(8123, 0, null));
            jdbc.update("UPDATE supplier_product SET recommend_score=9999,score_status='READY' WHERE supplier_id=? AND remark=?", supplierId, fixture);
            assertSnapshots(orderId, Arrays.asList(8123, 0, null));
            assertEquals(1, inboundCount(orderId));
            assertNotEquals(0, envelope(HttpMethod.POST, "/purchase/orders/" + orderId + "/approve", Map.of("version", submittedVersion)).path("code").asInt());
            assertNotEquals(0, envelope(HttpMethod.POST, "/purchase/orders/" + orderId + "/approve", Map.of("version", orderVersion(orderId))).path("code").asInt());
            assertEquals(1, inboundCount(orderId));

            // 独立连接占有supplier行锁，评分事务提交的新推荐分应被等待后的审核读取，而不是锁前旧快照。
            Long waitingOrder = createOrder(1);
            request(HttpMethod.POST, "/purchase/orders/" + waitingOrder + "/submit", Map.of("version", orderVersion(waitingOrder)));
            int waitingVersion = orderVersion(waitingOrder);
            try (var blocker = jdbc.getDataSource().getConnection(); var executor = Executors.newSingleThreadExecutor()) {
                blocker.setAutoCommit(false);
                try (var lockSql = blocker.prepareStatement("SELECT id FROM supplier WHERE id=? AND remark=? FOR UPDATE")) {
                    lockSql.setLong(1, supplierId);
                    lockSql.setString(2, fixture);
                    try (var row = lockSql.executeQuery()) { assertTrue(row.next()); }
                }
                var approval = executor.submit(() -> request(HttpMethod.POST,
                        "/purchase/orders/" + waitingOrder + "/approve", Map.of("version", waitingVersion)));
                try {
                    Thread.sleep(2000);
                    assertFalse(approval.isDone(), "审核应等待供应商行锁，不能在等待前冻结旧分数");
                    try (var newScore = blocker.prepareStatement("UPDATE supplier_product SET recommend_score=9234 WHERE id=? AND remark=?")) {
                        newScore.setLong(1, relationships.get(0));
                        newScore.setString(2, fixture);
                        assertEquals(1, newScore.executeUpdate());
                    }
                    blocker.commit();
                } finally { blocker.rollback(); }
                approval.get(30, TimeUnit.SECONDS);
            }
            assertSnapshots(waitingOrder, List.of(9234));
            assertEquals(1, inboundCount(waitingOrder));
            assertSnapshots(orderId, Arrays.asList(8123, 0, null));
            System.out.println("PURCHASE_SNAPSHOT_HTTP_VERIFIED ready8123=true zeroPreserved=true missingNull=true historicalImmutable=true afterWait9234=true duplicateInbound=false");

            // 库存余额仅给新仓库/新产品建立边界夹具，HTTP查询走实际联表Mapper和实际汇总逻辑。
            long[] stock = {100, 125, 100, 100, 126};
            for (int index = 0; index < 5; index++) insertStock(warehouses.get(0), index, stock[index], index == 2 ? 100 : 0);
            insertStock(warehouses.get(1), 0, 0, 0);
            String query = "/warehouse/stocks?warehouseId=" + warehouses.get(0) + "&pageSize=100";
            JsonNode all = request(HttpMethod.GET, query, null);
            assertEquals(5, all.path("records").size());
            assertEquals(2, all.path("summary").path("lowStockCount").asInt());
            assertEquals(1, all.path("summary").path("noAvailableCount").asInt());
            for (JsonNode record : all.path("records")) {
                assertEquals(warehouses.get(0).toString(), record.path("warehouseId").asText());
                assertTrue(record.has("safetyStockQty"), "安全库存投影必须返回业务字段");
                boolean zeroSafety = record.path("productId").asText().equals(products.get(3).toString());
                assertEquals(0, new BigDecimal(zeroSafety ? "0" : "1.25").compareTo(record.path("safetyStockQty").decimalValue()));
                assertFalse(record.has("safetyStockQtyStored"));
            }
            JsonNode risks = request(HttpMethod.GET, query + "&riskOnly=true", null);
            assertEquals(3, risks.path("records").size());
            assertEquals(2, risks.path("summary").path("lowStockCount").asInt());
            assertEquals(1, risks.path("summary").path("noAvailableCount").asInt());
            for (int index : List.of(0, 1, 2)) assertEquals(1, request(HttpMethod.GET,
                    query + "&riskOnly=true&productCode=" + productCodes.get(index), null).path("records").size());
            for (int index : List.of(3, 4)) assertEquals(0, request(HttpMethod.GET,
                    query + "&riskOnly=true&productCode=" + productCodes.get(index), null).path("records").size());
            assertEquals(2, request(HttpMethod.GET, query + "&riskOnly=true&inventoryHealth=LOW_STOCK", null).path("records").size());
            assertEquals(0, request(HttpMethod.GET, query + "&riskOnly=true&inventoryHealth=NORMAL", null).path("records").size());
            assertEquals(1, request(HttpMethod.GET, query + "&riskOnly=true&inventoryHealth=NO_AVAILABLE", null).path("records").size());
            System.out.println("STOCK_HTTP_SQL_VERIFIED safetyStock125To1_25=true lowSummary=2 zeroAvailable=1 equalityRisk=true zeroSafetyNormal=true aboveNormal=true warehouseAndProductIsolation=true");
        } finally {
            try { if (token != null) request(HttpMethod.POST, "/auth/logout", null); }
            finally { cleanup(); }
        }
    }

    /** 固定结构的订单创建，金额用字符串遵守正式接口契约。 */
    private Long createOrder(int itemCount) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < itemCount; index++) items.add(Map.of(
                "supplierProductId", relationships.get(index).toString(), "productId", products.get(index).toString(),
                "quantityPrecision", 2, "quantity", 1.25, "unitPrice", "10.00", "remark", fixture));
        Long id = request(HttpMethod.POST, "/purchase/orders", Map.of(
                "supplierId", supplierId.toString(), "warehouseId", warehouses.get(0).toString(),
                "expectedArrivalDate", LocalDate.now().plusDays(2).toString(), "items", items, "remark", fixture))
                .path("purchaseOrderId").asLong();
        assertTrue(id > 0);
        return id;
    }

    /** 同时核对实际数据库INT×100和HTTP业务小数，null与真实零分严格区分。 */
    private void assertSnapshots(Long orderId, List<Integer> expected) throws Exception {
        JsonNode detail = request(HttpMethod.GET, "/purchase/orders/" + orderId, null);
        assertEquals(expected.size(), detail.path("items").size());
        for (int index = 0; index < expected.size(); index++) {
            Long relationship = relationships.get(index);
            JsonNode item = null;
            for (JsonNode candidate : detail.path("items")) if (relationship.toString().equals(candidate.path("supplierProductId").asText())) item = candidate;
            assertNotNull(item);
            Integer raw = jdbc.queryForObject("SELECT selected_supplier_score FROM purchase_order_item WHERE purchase_order_id=? AND supplier_product_id=?", Integer.class, orderId, relationship);
            assertEquals(expected.get(index), raw);
            if (raw == null) {
                assertTrue(item.has("selectedSupplierScore"), "无推荐分也必须明确返回字段");
                assertTrue(item.path("selectedSupplierScore").isNull(), "无推荐分必须返回null，不是0或省略字段");
            }
            else assertEquals(0, BigDecimal.valueOf(raw, 2).compareTo(item.path("selectedSupplierScore").decimalValue()));
        }
    }

    /** 实际库存表使用100倍整数，编码与名称来自本测试创建的主数据。 */
    private void insertStock(Long warehouseId, int productIndex, long stock, long locked) {
        var warehouse = jdbc.queryForMap("SELECT warehouse_code,warehouse_name FROM warehouse WHERE id=? AND remark=?", warehouseId, fixture);
        jdbc.update("INSERT INTO warehouse_stock(id,warehouse_id,warehouse_code,warehouse_name,product_id,product_code,product_name,unit_name,stock_qty,locked_qty) VALUES(?,?,?,?,?,?,?,?,?,?)",
                IdWorker.getId(), warehouseId, warehouse.get("warehouse_code"), warehouse.get("warehouse_name"), products.get(productIndex),
                productCodes.get(productIndex), fixture + "-" + productIndex, "件", stock, locked);
    }

    /** 真实HTTP调用不输出身份凭据，失败响应只输出业务说明。 */
    private JsonNode request(HttpMethod method, String path, Object body) throws Exception {
        JsonNode response = envelope(method, path, body);
        assertEquals(0, response.path("code").asInt(), path + "：" + response.path("message").asText());
        return response.path("data");
    }

    /** 返回完整响应供失败场景断言，正常场景由request核对业务成功。 */
    private JsonNode envelope(HttpMethod method, String path, Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.set(tokenName, token);
        var response = http.exchange(path, method, new HttpEntity<>(body, headers), String.class);
        assertEquals(200, response.getStatusCode().value(), "HTTP失败：" + path);
        return mapper.readTree(response.getBody());
    }

    /** 使用实际订单版本，避免测试写死版本掩盖并发或二次更新问题。 */
    private int orderVersion(Long orderId) { return jdbc.queryForObject("SELECT version FROM purchase_order WHERE id=?", Integer.class, orderId); }
    /** 来源绑定本测试订单的入库单数量，重复审核不得增加。 */
    private int inboundCount(Long orderId) { return jdbc.queryForObject("SELECT COUNT(*) FROM inbound_bill WHERE source_type='PURCHASE_ORDER' AND source_id=?", Integer.class, orderId); }

    /** 仅使用唯一备注恢复自己的业务ID，再按主键从子表到主表删除，不清共享Redis。 */
    private void cleanup() {
        for (Long order : jdbc.queryForList("SELECT id FROM purchase_order WHERE remark=?", Long.class, fixture)) {
            for (Long inbound : jdbc.queryForList("SELECT id FROM inbound_bill WHERE source_type='PURCHASE_ORDER' AND source_id=?", Long.class, order)) {
                jdbc.update("DELETE FROM inbound_bill_item WHERE inbound_bill_id=?", inbound);
                jdbc.update("DELETE FROM inbound_bill WHERE id=?", inbound);
            }
            jdbc.update("DELETE FROM purchase_order_item WHERE purchase_order_id=?", order);
            jdbc.update("DELETE FROM purchase_order WHERE id=? AND remark=?", order, fixture);
        }
        for (Long warehouse : jdbc.queryForList("SELECT id FROM warehouse WHERE remark=?", Long.class, fixture)) {
            jdbc.update("DELETE FROM warehouse_stock WHERE warehouse_id=?", warehouse);
            jdbc.update("DELETE FROM warehouse WHERE id=? AND remark=?", warehouse, fixture);
        }
        for (Long supplier : jdbc.queryForList("SELECT id FROM supplier WHERE remark=?", Long.class, fixture)) {
            jdbc.update("DELETE FROM supplier_score_change_log WHERE supplier_id=?", supplier);
            for (Long relationship : jdbc.queryForList("SELECT id FROM supplier_product WHERE supplier_id=? AND remark=?", Long.class, supplier, fixture)) jdbc.update("DELETE FROM supplier_product WHERE id=? AND remark=?", relationship, fixture);
            jdbc.update("DELETE FROM supplier WHERE id=? AND remark=?", supplier, fixture);
        }
        for (Long product : jdbc.queryForList("SELECT id FROM product WHERE remark=?", Long.class, fixture)) jdbc.update("DELETE FROM product WHERE id=? AND remark=?", product, fixture);
        for (String table : List.of("purchase_order", "supplier", "product", "warehouse")) assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE remark=?", Integer.class, fixture));
        System.out.println("PURCHASE_STOCK_HTTP_FIXTURE_CLEANED exactOwnedIds=true sharedRedisUntouched=true fixture=" + fixture);
    }
}
