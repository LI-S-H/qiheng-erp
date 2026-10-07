package com.qiheng.erp.purchase;

import com.qiheng.erp.common.util.QtyUtil;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集成测试:直连 MySQL(localhost:13307),验证本次改动。
 * <p>
 * <ul>
 *     <li>验证 #3: 供货产品评分筛选的 INT×100 边界比较使用 long，无浮点问题</li>
 *     <li>验证 #4: chk_supplier_score_log_metric_type CHECK 约束删除 RULE 后,RULE 被拒</li>
 * </ul>
 * 启用条件：-Dmysql.it=true。
 */
@EnabledIfSystemProperty(named = "mysql.it", matches = "true")
class SupplierProductSqlIT {

    private static final String URL = "jdbc:mysql://localhost:13307/erp?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true";
    private static final String USER = "root";
    private static final String PASSWORD = "123456";

    private static Connection conn;

    @BeforeAll
    static void connect() throws SQLException, ClassNotFoundException {
        Class.forName("com.mysql.cj.jdbc.Driver");
        conn = DriverManager.getConnection(URL, USER, PASSWORD);
        conn.setAutoCommit(false);
    }

    @AfterAll
    static void close() throws SQLException {
        if (conn != null && !conn.isClosed()) {
            conn.close();
        }
    }

    // ========== #3 SQL * 100 修复验证 ==========
    //
    // XML 修复后用 <bind name="x" value="@QtyUtil@toStoredInt(dto.xxx)" />,
    // 把 70.99 转成 long 7099,SQL 内 INT 直接 >= 7099。
    //
    // 关键:转换是 MyBatis 内存,不是 MySQL 浮点,所以语义确定。
    // 此处手工调 QtyUtil.toStoredInt 模拟 XML 行为,然后 SQL 用 long 值。

    @Test
    void qtyUtilToStoredIntShouldRound70_99To7099() {
        // 这是 XML <bind> 中实际调用的逻辑,验证其正确性
        Integer stored = QtyUtil.toStoredInt(new BigDecimal("70.99"));
        assertEquals(Integer.valueOf(7099), stored);
    }

    @Test
    void qtyUtilToStoredIntShouldHandleBoundaryValues() {
        assertEquals(Integer.valueOf(0), QtyUtil.toStoredInt(new BigDecimal("0.00")));
        assertEquals(Integer.valueOf(10000), QtyUtil.toStoredInt(new BigDecimal("100.00")));
        assertEquals(Integer.valueOf(100), QtyUtil.toStoredInt(new BigDecimal("1.00")));
        assertEquals(Integer.valueOf(1), QtyUtil.toStoredInt(new BigDecimal("0.01")));
    }

    @Test
    void qtyUtilToStoredShouldRoundMinOrderQty() {
        // supplier_product.min_order_qty 是 BIGINT,toStored 用 Long
        Long stored = QtyUtil.toStored(new BigDecimal("10.01"));
        assertEquals(Long.valueOf(1001), stored);
    }

    @Test
    void supplierProductScoreFilterShouldExclude7098WhenMinIs7099Long() throws SQLException {
        // 用不同 supplierId 避开 (supplier_id, product_id) UNIQUE 约束
        long testProductId = 1920000999001L;
        long supplierIdA = 1900000999001L;
        long supplierIdB = 1900000999011L;
        long spA = 2011000999001L;
        long spB = 2011000999002L;

        try {
            cleanupSupplierProduct(spA, spB);
            cleanupBySupplierAndProduct(supplierIdA, testProductId);
            cleanupBySupplierAndProduct(supplierIdB, testProductId);
            ensureSupplier(supplierIdA, "TEST-S-001");
            ensureSupplier(supplierIdB, "TEST-S-011");
            ensureProduct(testProductId, "TEST-P-001");
            insertSupplierProductWithQuality(spA, supplierIdA, testProductId, 7098);
            insertSupplierProductWithQuality(spB, supplierIdB, testProductId, 7099);
            conn.commit();

            Integer minStored = QtyUtil.toStoredInt(new BigDecimal("70.99"));
            long totalA = countByQualityScoreAndSupplier(testProductId, supplierIdA, minStored.longValue(), null);
            long totalB = countByQualityScoreAndSupplier(testProductId, supplierIdB, minStored.longValue(), null);

            assertEquals(0, totalA, "supplierIdA 数据 7098 < 7099 应被排除");
            assertEquals(1, totalB, "supplierIdB 数据 7099 >= 7099 应被命中");
            assertEquals(1, totalA + totalB, "quality_score >= 7099(long 比较)应只命中 spB,不含 spA(7098)");
        } finally {
            cleanupSupplierProduct(spA, spB);
            cleanupBySupplierAndProduct(supplierIdA, testProductId);
            cleanupBySupplierAndProduct(supplierIdB, testProductId);
            conn.commit();
        }
    }

    @Test
    void supplierProductScoreFilterShouldInclude7098WhenMinIs70_98Long() throws SQLException {
        // 反向:qualityScoreMin = 70.98 → 应包含 7098 和 7099 两条
        long testProductId = 1920000999002L;
        long supplierIdA = 1900000999002L;
        long supplierIdB = 1900000999022L;
        long spA = 2011000999003L;
        long spB = 2011000999004L;

        try {
            cleanupSupplierProduct(spA, spB);
            cleanupBySupplierAndProduct(supplierIdA, testProductId);
            cleanupBySupplierAndProduct(supplierIdB, testProductId);
            ensureSupplier(supplierIdA, "TEST-S-002");
            ensureSupplier(supplierIdB, "TEST-S-022");
            ensureProduct(testProductId, "TEST-P-002");
            insertSupplierProductWithQuality(spA, supplierIdA, testProductId, 7098);
            insertSupplierProductWithQuality(spB, supplierIdB, testProductId, 7099);
            conn.commit();

            Integer minStored = QtyUtil.toStoredInt(new BigDecimal("70.98"));
            long totalA = countByQualityScoreAndSupplier(testProductId, supplierIdA, minStored.longValue(), null);
            long totalB = countByQualityScoreAndSupplier(testProductId, supplierIdB, minStored.longValue(), null);

            assertEquals(1, totalA, "supplierIdA 数据 7098 >= 7098 应被命中");
            assertEquals(1, totalB, "supplierIdB 数据 7099 >= 7098 应被命中");
            assertEquals(2, totalA + totalB, "quality_score >= 7098(long 比较)应命中 spA 和 spB 两条");
        } finally {
            cleanupSupplierProduct(spA, spB);
            cleanupBySupplierAndProduct(supplierIdA, testProductId);
            cleanupBySupplierAndProduct(supplierIdB, testProductId);
            conn.commit();
        }
    }

    @Test
    void supplierProductMinOrderQtyFilterShouldCompareAsLong() throws SQLException {
        // 用不同 supplierId 避开 UNIQUE
        long testProductId = 1920000999003L;
        long supplierIdA = 1900000999003L;
        long supplierIdB = 1900000999033L;
        long spA = 2011000999005L;
        long spB = 2011000999006L;

        try {
            cleanupSupplierProduct(spA, spB);
            cleanupBySupplierAndProduct(supplierIdA, testProductId);
            cleanupBySupplierAndProduct(supplierIdB, testProductId);
            ensureSupplier(supplierIdA, "TEST-S-003");
            ensureSupplier(supplierIdB, "TEST-S-033");
            ensureProduct(testProductId, "TEST-P-003");
            insertSupplierProductWithMinQty(spA, supplierIdA, testProductId, 1000);
            insertSupplierProductWithMinQty(spB, supplierIdB, testProductId, 100000);
            conn.commit();

            Long minStored = QtyUtil.toStored(new BigDecimal("10.01"));
            long totalA = countByMinOrderQtyAndSupplier(testProductId, supplierIdA, minStored, null);
            long totalB = countByMinOrderQtyAndSupplier(testProductId, supplierIdB, minStored, null);

            assertEquals(0, totalA, "supplierIdA 数据 1000 < 1001 应被排除");
            assertEquals(1, totalB, "supplierIdB 数据 100000 >= 1001 应被命中");
        } finally {
            cleanupSupplierProduct(spA, spB);
            cleanupBySupplierAndProduct(supplierIdA, testProductId);
            cleanupBySupplierAndProduct(supplierIdB, testProductId);
            conn.commit();
        }
    }

    // ========== #4 SQL CHECK 删 RULE 验证 ==========

    @Test
    void insertChangeLogWithRuleMetricTypeShouldFailDueToCheckConstraint() {
        long testSupplierId = 1900000999004L;

        SQLException exception = assertThrows(SQLException.class, () -> {
            ensureSupplier(testSupplierId, "TEST-S-004");
            insertChangeLogWithMetricType(testSupplierId, "RULE");
        });

        String msg = exception.getMessage().toLowerCase();
        assertTrue(msg.contains("check") || msg.contains("chk_supplier_score_log_metric_type"),
                "应该报 CHECK 约束错误,实际: " + exception.getMessage());
    }

    @Test
    void insertChangeLogWithValidMetricTypesShouldSucceed() throws SQLException {
        long testSupplierId = 1900000999005L;
        try {
            ensureSupplier(testSupplierId, "TEST-S-005");
            // 验证 4 个合法值都能插入
            insertChangeLogWithMetricType(testSupplierId, "PRICE");
            insertChangeLogWithMetricType(testSupplierId, "QUALITY");
            insertChangeLogWithMetricType(testSupplierId, "DELIVERY");
            insertChangeLogWithMetricType(testSupplierId, "SERVICE");
            conn.commit();

            long count = countChangeLogBySupplier(testSupplierId);
            assertEquals(4, count, "4 个合法 metric_type 应该全部插入成功");
        } finally {
            cleanupChangeLog(testSupplierId);
            conn.commit();
        }
    }

    // ========== 工具方法 ==========

    private long countByQualityScore(long productId, Long minStored, Long maxStored) throws SQLException {
        String sql = "SELECT COUNT(*) FROM supplier_product WHERE deleted = 0 AND product_id = ? " +
                (minStored != null ? "AND quality_score >= ? " : "") +
                (maxStored != null ? "AND quality_score <= ? " : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setLong(idx++, productId);
            if (minStored != null) ps.setLong(idx++, minStored);
            if (maxStored != null) ps.setLong(idx++, maxStored);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countByQualityScoreAndSupplier(long productId, long supplierId, Long minStored, Long maxStored) throws SQLException {
        String sql = "SELECT COUNT(*) FROM supplier_product WHERE deleted = 0 AND product_id = ? AND supplier_id = ? " +
                (minStored != null ? "AND quality_score >= ? " : "") +
                (maxStored != null ? "AND quality_score <= ? " : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setLong(idx++, productId);
            ps.setLong(idx++, supplierId);
            if (minStored != null) ps.setLong(idx++, minStored);
            if (maxStored != null) ps.setLong(idx++, maxStored);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countByMinOrderQty(long productId, Long minStored, Long maxStored) throws SQLException {
        String sql = "SELECT COUNT(*) FROM supplier_product WHERE deleted = 0 AND product_id = ? " +
                (minStored != null ? "AND min_order_qty >= ? " : "") +
                (maxStored != null ? "AND min_order_qty <= ? " : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setLong(idx++, productId);
            if (minStored != null) ps.setLong(idx++, minStored);
            if (maxStored != null) ps.setLong(idx++, maxStored);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private long countByMinOrderQtyAndSupplier(long productId, long supplierId, Long minStored, Long maxStored) throws SQLException {
        String sql = "SELECT COUNT(*) FROM supplier_product WHERE deleted = 0 AND product_id = ? AND supplier_id = ? " +
                (minStored != null ? "AND min_order_qty >= ? " : "") +
                (maxStored != null ? "AND min_order_qty <= ? " : "");
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int idx = 1;
            ps.setLong(idx++, productId);
            ps.setLong(idx++, supplierId);
            if (minStored != null) ps.setLong(idx++, minStored);
            if (maxStored != null) ps.setLong(idx++, maxStored);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void ensureSupplier(long id, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO supplier (id, supplier_code, supplier_name, status, deleted, " +
                        "create_time, update_time, updated_by_id, updated_by_name, version) " +
                        "VALUES (?, ?, ?, 1, 0, NOW(), NOW(), 1, 'admin', 0)")) {
            ps.setLong(1, id);
            ps.setString(2, code);
            ps.setString(3, "测试供应商" + id);
            ps.executeUpdate();
        }
    }

    private void ensureProduct(long id, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT IGNORE INTO product (id, product_code, product_name, quantity_precision, status, deleted, " +
                        "create_time, update_time, version) " +
                        "VALUES (?, ?, ?, 0, 1, 0, NOW(), NOW(), 0)")) {
            ps.setLong(1, id);
            ps.setString(2, code);
            ps.setString(3, "测试产品" + id);
            ps.executeUpdate();
        }
    }

    private void insertSupplierProductWithQuality(long id, long supplierId, long productId, int qualityScore) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO supplier_product (id, supplier_id, product_id, quoted_purchase_price, quoted_price_reason, " +
                        "min_order_qty, quality_score, price_score, ai_score, score_basis_amount, score_status, " +
                        "status, deleted, create_time, update_time, updated_by_id, updated_by_name, version) " +
                        "VALUES (?, ?, ?, NULL, '', 1000, ?, 7500, 8500, 0, 'READY', 1, 0, NOW(), NOW(), 1, 'admin', 0)")) {
            ps.setLong(1, id);
            ps.setLong(2, supplierId);
            ps.setLong(3, productId);
            ps.setInt(4, qualityScore);
            ps.executeUpdate();
        }
    }

    private void insertSupplierProductWithMinQty(long id, long supplierId, long productId, long minOrderQty) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO supplier_product (id, supplier_id, product_id, quoted_purchase_price, quoted_price_reason, " +
                        "min_order_qty, quality_score, price_score, ai_score, score_basis_amount, score_status, " +
                        "status, deleted, create_time, update_time, updated_by_id, updated_by_name, version) " +
                        "VALUES (?, ?, ?, NULL, '', ?, 7500, 7500, 8500, 0, 'READY', 1, 0, NOW(), NOW(), 1, 'admin', 0)")) {
            ps.setLong(1, id);
            ps.setLong(2, supplierId);
            ps.setLong(3, productId);
            ps.setLong(4, minOrderQty);
            ps.executeUpdate();
        }
    }

    private void cleanupSupplierProduct(long... ids) throws SQLException {
        for (long id : ids) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM supplier_product WHERE id = ?")) {
                ps.setLong(1, id);
                ps.executeUpdate();
            }
        }
    }

    private void cleanupBySupplierAndProduct(long supplierId, long productId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM supplier_product WHERE supplier_id = ? AND product_id = ?")) {
            ps.setLong(1, supplierId);
            ps.setLong(2, productId);
            ps.executeUpdate();
        }
    }

    private void insertChangeLogWithMetricType(long supplierId, String metricType) throws SQLException {
        String sql = "INSERT INTO supplier_score_change_log " +
                "(id, change_key, batch_no, rule_version, supplier_id, supplier_product_id, " +
                "metric_type, metric_score_before, metric_score_after, " +
                "product_recommend_score_before, product_recommend_score_after, " +
                "supplier_overall_score_before, supplier_overall_score_after, " +
                "trigger_type, related_business_id, related_business_no, " +
                "operator_id, operator_name, reason, create_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            long id = 1900000000999000L + System.nanoTime() % 100000;
            ps.setLong(1, id);
            ps.setString(2, "test-" + metricType + "-" + id);
            ps.setString(3, "test-batch");
            ps.setString(4, "v1");
            ps.setLong(5, supplierId);
            ps.setNull(6, java.sql.Types.BIGINT);
            ps.setString(7, metricType);
            ps.setInt(8, 8000);
            ps.setInt(9, 8500);
            ps.setInt(10, 8000);
            ps.setInt(11, 8500);
            ps.setInt(12, 8000);
            ps.setInt(13, 8500);
            ps.setString(14, "RECALC");
            ps.setNull(15, java.sql.Types.BIGINT);
            ps.setString(16, "");
            ps.setLong(17, 1L);
            ps.setString(18, "admin");
            ps.setString(19, "test");
            ps.setTimestamp(20, Timestamp.valueOf(LocalDateTime.now()));
            ps.executeUpdate();
        }
    }

    private long countChangeLogBySupplier(long supplierId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id = ?")) {
            ps.setLong(1, supplierId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private void cleanupChangeLog(long supplierId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM supplier_score_change_log WHERE supplier_id = ? AND change_key LIKE 'test-%'")) {
            ps.setLong(1, supplierId);
            ps.executeUpdate();
        }
    }
}
