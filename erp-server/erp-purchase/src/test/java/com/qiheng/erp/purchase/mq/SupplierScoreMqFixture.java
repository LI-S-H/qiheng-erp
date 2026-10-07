package com.qiheng.erp.purchase.mq;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 真实评分消息测试的数据夹具，仅复制已有样本并覆盖隔离业务关系，不触碰库存。
 * 构造过程只读；创建、清理均以精确主键在独立事务中执行。
 */
public final class SupplierScoreMqFixture {
    private static final List<String> TABLES = List.of("supplier", "product", "supplier_product",
            "purchase_order", "purchase_order_item", "inbound_bill", "inbound_bill_item");
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final String runId;
    private final LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
    private final Map<String, Map<String, Object>> templates = new LinkedHashMap<>();
    private final Map<String, List<String>> columns = new LinkedHashMap<>();
    private final Map<String, List<Long>> reservedIds = new LinkedHashMap<>();
    private final List<Sample> samples = new ArrayList<>();
    private boolean created;
    private long nextId = System.currentTimeMillis() * 1000;

    /**
     * 只读加载真实样本、可写列，并确认所有预留主键尚未使用。
     *
     * @param jdbc 调用方提供的真实数据库客户端
     * @param runId 本次运行唯一标识，只允许字母和数字，最长二十四位
     */
    public SupplierScoreMqFixture(JdbcTemplate jdbc, String runId) {
        if (runId == null || !runId.matches("[a-zA-Z0-9]{1,24}")) {
            throw new IllegalArgumentException("测试运行标识必须为一至二十四位字母或数字");
        }
        this.jdbc = jdbc;
        this.runId = runId;
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource(), "真实数据源不能为空")));
        for (String table : TABLES) {
            List<String> writable = jdbc.queryForList("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                    + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? AND EXTRA NOT LIKE '%GENERATED%' "
                    + "ORDER BY ORDINAL_POSITION", String.class, table);
            if (!writable.contains("id")) throw new IllegalStateException("缺少测试表或主键：" + table);
            columns.put(table, writable);
            String filter = writable.contains("deleted") ? " WHERE deleted=0" : "";
            if (table.equals("purchase_order")) filter += " AND status='INBOUND_DONE'";
            if (table.equals("inbound_bill")) filter += " AND status='CONFIRMED' AND inbound_type='PURCHASE_IN'";
            List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM `" + table + "`" + filter
                    + " ORDER BY id LIMIT 1");
            if (rows.isEmpty()) throw new IllegalStateException("缺少可复制的有效样本：" + table);
            templates.put(table, new LinkedHashMap<>(rows.getFirst()));
            reservedIds.put(table, new ArrayList<>());
        }
        for (String label : List.of("cold", "warm", "merged")) {
            long supplierId = reserve("supplier");
            List<Long> productIds = List.of(reserve("product"), reserve("product"));
            List<Long> relationIds = List.of(reserve("supplier_product"), reserve("supplier_product"));
            List<Long> orders = new ArrayList<>();
            List<Long> orderItems = new ArrayList<>();
            List<Long> inbounds = new ArrayList<>();
            List<Long> inboundItems = new ArrayList<>();
            for (int order = 0; order < (label.equals("merged") ? 2 : 1); order++) {
                orders.add(reserve("purchase_order"));
                inbounds.add(reserve("inbound_bill"));
                for (int product = 0; product < 2; product++) {
                    orderItems.add(reserve("purchase_order_item"));
                    inboundItems.add(reserve("inbound_bill_item"));
                }
            }
            samples.add(new Sample(label, supplierId, productIds, relationIds, List.copyOf(orders),
                    List.copyOf(orderItems), List.copyOf(inbounds), List.copyOf(inboundItems)));
        }
        assertIdsUnused();
    }

    /**
     * 原子插入全部隔离样本；任一约束不满足时整个创建事务回滚。
     *
     * @return 按冷缓存、增量缓存、多单合并排列的样本主键
     */
    public List<Sample> create() {
        if (created) throw new IllegalStateException("测试数据已经创建");
        transaction.executeWithoutResult(status -> {
            assertIdsUnused();
            for (Sample sample : samples) createSample(sample);
        });
        created = true;
        return samples();
    }

    /**
     * 返回测试样本精确主键，供消息触发、评分断言和 Redis 清理使用。
     *
     * @return 不可变的样本清单
     */
    public List<Sample> samples() {
        return List.copyOf(samples);
    }

    /**
     * 查询当前测试供应商评分，便于测试在消费完成后独立断言落库结果。
     *
     * @param sample 测试样本
     * @return 当前供应商完整数据库行
     */
    public Map<String, Object> supplierRow(Sample sample) {
        return jdbc.queryForMap("SELECT * FROM supplier WHERE id=?", sample.supplierId());
    }

    /**
     * 仅删除本次创建的日志和精确主键；不能用于清理未由本夹具创建的数据。
     */
    public void cleanup() {
        if (!created) return;
        transaction.executeWithoutResult(status -> {
            for (Sample sample : samples) {
                jdbc.update("DELETE FROM supplier_score_change_log WHERE supplier_id=?", sample.supplierId());
            }
            for (String table : List.of("inbound_bill_item", "inbound_bill", "purchase_order_item",
                    "purchase_order", "supplier_product", "supplier", "product")) {
                for (Long id : reservedIds.get(table)) jdbc.update("DELETE FROM `" + table + "` WHERE id=?", id);
            }
        });
        created = false;
    }

    /** 复制有效业务关系，只覆盖本次评分所需事实，保留样本中其他必填字段。 */
    private void createSample(Sample sample) {
        String supplierCode = code("S", sample.supplierId());
        String supplierName = "评分消息测试-" + sample.label() + "-" + runId;
        Map<String, Object> supplier = row("supplier", sample.supplierId());
        supplier.put("supplier_code", supplierCode);
        supplier.put("supplier_name", supplierName);
        supplier.put("service_score", 8000);
        supplier.put("service_score_reason", "真实消息测试服务评分");
        supplier.put("score_basis_amount", 30000L);
        clearScores(supplier);
        insert("supplier", supplier);
        for (int product = 0; product < 2; product++) {
            Long productId = sample.productIds().get(product);
            Map<String, Object> item = row("product", productId);
            item.put("product_code", code("P", productId));
            item.put("product_name", "评分测试产品-" + product + "-" + runId);
            item.put("barcode", code("B", productId));
            item.put("reference_purchase_price", new BigDecimal("100.00"));
            item.put("quantity_precision", 0);
            insert("product", item);
            Map<String, Object> relation = row("supplier_product", sample.supplierProductIds().get(product));
            relation.put("supplier_id", sample.supplierId());
            relation.put("product_id", productId);
            relation.put("quoted_purchase_price", product == 0 ? 10000L : 12000L);
            relation.put("quoted_price_reason", "真实消息测试有效报价");
            relation.put("quoted_price_updated_at", Timestamp.valueOf(now));
            relation.put("quote_valid_until", now.toLocalDate().plusDays(30));
            relation.put("score_basis_amount", product == 0 ? 10000L : 20000L);
            clearScores(relation);
            insert("supplier_product", relation);
        }
        for (int order = 0; order < sample.orderIds().size(); order++) {
            Long orderId = sample.orderIds().get(order);
            String purchaseNo = code("PO", orderId);
            Map<String, Object> po = row("purchase_order", orderId);
            po.put("purchase_no", purchaseNo);
            po.put("supplier_id", sample.supplierId());
            po.put("supplier_code", supplierCode);
            po.put("supplier_name", supplierName);
            po.put("status", "INBOUND_DONE");
            po.put("total_amount", 300000L);
            po.put("expected_arrival_date", now.toLocalDate().minusDays(1));
            po.put("approved_at", Timestamp.valueOf(now.minusDays(2)));
            po.put("fully_received_at", Timestamp.valueOf(now));
            insert("purchase_order", po);
            Long inboundId = sample.inboundIds().get(order);
            String inboundNo = code("IB", inboundId);
            Map<String, Object> inbound = row("inbound_bill", inboundId);
            inbound.put("inbound_no", inboundNo);
            inbound.put("inbound_type", "PURCHASE_IN");
            inbound.put("source_type", "PURCHASE_ORDER");
            inbound.put("source_id", orderId);
            inbound.put("source_no", purchaseNo);
            inbound.put("source_party_id", sample.supplierId());
            inbound.put("source_party_name", supplierName);
            inbound.put("entry_mode", "SOURCE_GENERATED");
            inbound.put("warehouse_id", po.get("warehouse_id"));
            inbound.put("warehouse_name", po.get("warehouse_name"));
            inbound.put("status", "CONFIRMED");
            inbound.put("expected_arrival_date", now.toLocalDate().minusDays(1));
            inbound.put("confirmed_at", Timestamp.valueOf(now));
            insert("inbound_bill", inbound);
            for (int product = 0; product < 2; product++) {
                int offset = order * 2 + product;
                Long productId = sample.productIds().get(product);
                Long orderItemId = sample.orderItemIds().get(offset);
                long unitPrice = product == 0 ? 10000L : 20000L;
                Map<String, Object> poi = row("purchase_order_item", orderItemId);
                poi.put("purchase_order_id", orderId);
                poi.put("purchase_no", purchaseNo);
                poi.put("supplier_product_id", sample.supplierProductIds().get(product));
                poi.put("product_id", productId);
                poi.put("product_code", code("P", productId));
                poi.put("product_name", "评分测试产品-" + product + "-" + runId);
                poi.put("quantity_precision", 0);
                poi.put("quantity", 1000L);
                poi.put("inbound_qty", 1000L);
                poi.put("unit_price", unitPrice);
                poi.put("total_amount", unitPrice * 10);
                insert("purchase_order_item", poi);
                Map<String, Object> ibi = row("inbound_bill_item", sample.inboundItemIds().get(offset));
                ibi.put("inbound_bill_id", inboundId);
                ibi.put("inbound_no", inboundNo);
                ibi.put("source_item_id", orderItemId);
                ibi.put("product_id", productId);
                ibi.put("product_code", code("P", productId));
                ibi.put("product_name", poi.get("product_name"));
                ibi.put("quantity_precision", 0);
                ibi.put("plan_qty", 1000L);
                ibi.put("processed_qty", 0L);
                ibi.put("current_qty", 1000L);
                ibi.put("pending_qty", 0L);
                ibi.put("unit_price", unitPrice);
                ibi.put("qualified_qty", product == 0 ? 800L : 500L);
                ibi.put("defective_qty", product == 0 ? 200L : 500L);
                ibi.put("stock_bill_item_id", null);
                insert("inbound_bill_item", ibi);
            }
        }
    }

    /** 清除样本原评分，避免消费成功断言被已有分数掩盖。 */
    private void clearScores(Map<String, Object> values) {
        for (String column : Set.of("overall_score", "delivery_score", "quality_score", "price_score",
                "recommend_score", "ai_score", "avg_delivery_days")) {
            if (values.containsKey(column)) values.put(column, null);
        }
        values.put("score_status", "NOT_READY");
    }

    /** 保留真实列的数据类型，覆盖审计字段，过滤数据库生成列。 */
    private Map<String, Object> row(String table, long id) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String column : columns.get(table)) result.put(column, templates.get(table).get(column));
        result.put("id", id);
        for (String column : List.of("create_time", "update_time")) {
            if (result.containsKey(column)) result.put(column, Timestamp.valueOf(now));
        }
        if (result.containsKey("deleted")) result.put("deleted", 0);
        if (result.containsKey("version")) result.put("version", 0);
        if (result.containsKey("status") && !table.equals("purchase_order") && !table.equals("inbound_bill")) {
            result.put("status", 1);
        }
        if (result.containsKey("remark")) result.put("remark", "隔离评分消息测试 " + runId);
        return result;
    }

    /** 拒绝静默忽略评分所需的新字段，数据库版本不匹配时直接失败。 */
    private void insert(String table, Map<String, Object> values) {
        if (!columns.get(table).containsAll(values.keySet())) {
            throw new IllegalStateException("测试字段不在实际数据库中：" + table + " " + values.keySet());
        }
        String names = String.join(",", values.keySet().stream().map(name -> "`" + name + "`").toList());
        String placeholders = String.join(",", values.keySet().stream().map(name -> "?").toList());
        jdbc.update("INSERT INTO `" + table + "` (" + names + ") VALUES (" + placeholders + ")",
                values.values().toArray());
    }

    /** 主键只供本次运行使用，拒绝覆盖已有数据。 */
    private long reserve(String table) {
        long id = ++nextId;
        reservedIds.get(table).add(id);
        return id;
    }

    /** 创建事务开始前再次核实主键，避免构造到插入间的占用变化。 */
    private void assertIdsUnused() {
        reservedIds.forEach((table, ids) -> ids.forEach(id -> {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM `" + table + "` WHERE id=?", Integer.class, id);
            if (count == null || count != 0) throw new IllegalStateException("隔离测试主键已占用：" + table + "/" + id);
        }));
    }

    /** 单号保留运行标识和精确主键，避免克隆原样本的唯一键。 */
    private String code(String prefix, long id) {
        return prefix + runId + Long.toString(id, 36);
    }

    /** 完整暴露精确测试关系，所有集合均为不可变列表。 */
    public record Sample(String label, long supplierId, List<Long> productIds, List<Long> supplierProductIds,
            List<Long> orderIds, List<Long> orderItemIds, List<Long> inboundIds, List<Long> inboundItemIds) {
    }
}
