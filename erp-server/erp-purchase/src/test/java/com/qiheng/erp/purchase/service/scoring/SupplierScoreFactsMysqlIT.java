package com.qiheng.erp.purchase.service.scoring;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.qiheng.erp.purchase.mapper.PurchaseOrderItemMapper;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.warehouse.mapper.InboundBillItemMapper;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigInteger;
import java.sql.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** 13307 真实数据只读对账，不触发缓存、评分落库或定时任务。 */
@EnabledIfSystemProperty(named = "score.mysql.it", matches = "true")
class SupplierScoreFactsMysqlIT {
    @Test
    void supplierThenProductRowLocksShouldSerializeWithoutDeadlock() throws Exception {
        String url = System.getProperty("score.it.url",
                "jdbc:mysql://localhost:13307/erp?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true");
        String user = System.getProperty("score.it.user", "root");
        String password = Objects.requireNonNull(System.getenv("ERP_IT_JDBC_PASSWORD"));
        long supplierId;
        long supplierProductId;
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT supplier_id,id FROM supplier_product WHERE deleted=0 ORDER BY supplier_id,id LIMIT 1")) {
            assertTrue(rs.next(), "真实库需存在供货关系用于锁顺序测试");
            supplierId = rs.getLong(1);
            supplierProductId = rs.getLong(2);
        }
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Callable<Void> lockInBusinessOrder = () -> {
            start.await();
            try (Connection connection = DriverManager.getConnection(url, user, password)) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                try {
                    // 报价与凌晨校正都先锁 supplier，再锁同一个 SP；测试结束只回滚行锁，不写业务数据。
                    try (PreparedStatement supplier = connection.prepareStatement("SELECT id FROM supplier WHERE id=? FOR UPDATE");
                         PreparedStatement product = connection.prepareStatement("SELECT id FROM supplier_product WHERE id=? FOR UPDATE")) {
                        supplier.setLong(1, supplierId);
                        product.setLong(1, supplierProductId);
                        try (ResultSet rs = supplier.executeQuery()) { assertTrue(rs.next()); }
                        try (ResultSet rs = product.executeQuery()) { assertTrue(rs.next()); }
                    }
                    Thread.sleep(100);
                } finally {
                    connection.rollback();
                }
            }
            return null;
        };
        try {
            Future<Void> first = pool.submit(lockInBusinessOrder);
            Future<Void> second = pool.submit(lockInBusinessOrder);
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void actualMapperFactsMustMatchIndependentSqlOracle() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("com.mysql.cj.jdbc.Driver");
        dataSource.setUrl(System.getProperty("score.it.url", "jdbc:mysql://localhost:13307/erp?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
        dataSource.setUsername(System.getProperty("score.it.user", "root"));
        dataSource.setPassword(Objects.requireNonNull(System.getenv("ERP_IT_JDBC_PASSWORD"), "请设置 ERP_IT_JDBC_PASSWORD"));
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true); configuration.setLogImpl(NoLoggingImpl.class);
        for (Class<?> mapper : List.of(PurchaseOrderMapper.class, PurchaseOrderItemMapper.class,
                SupplierProductMapper.class, InboundBillMapper.class, InboundBillItemMapper.class)) configuration.addMapper(mapper);
        MybatisSqlSessionFactoryBean bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(dataSource); bean.setConfiguration(configuration);
        try (SqlSession session = Objects.requireNonNull(bean.getObject()).openSession(false)) {
            Connection connection = session.getConnection();
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); connection.setReadOnly(true);
            SupplierScoreFactsAggregationService reader = new SupplierScoreFactsAggregationService(
                    session.getMapper(PurchaseOrderMapper.class), session.getMapper(PurchaseOrderItemMapper.class),
                    session.getMapper(InboundBillMapper.class), session.getMapper(InboundBillItemMapper.class), session.getMapper(SupplierProductMapper.class));
            LocalDate date = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            List<Long> suppliers = new ArrayList<>();
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT id FROM supplier WHERE deleted=0 ORDER BY id")) {
                while (rs.next()) suppliers.add(rs.getLong(1));
            }
            assertFalse(suppliers.isEmpty(), "真实库应存在供应商样本");
            long qualitySamples = 0, deliverySamples = 0, dueTotal = 0, penaltyTotal = 0;
            Long sampleSupplier = null;
            for (Long supplierId : suppliers) {
                long started = System.nanoTime();
                var quality = reader.queryQualityFacts(supplierId, date);
                var delivery = reader.queryDeliveryFacts(supplierId, date);
                Map<Long, SupplierScoreFactsAggregationService.QualityAmount> expected = qualityOracle(connection, supplierId, date);
                assertEquals(expected, quality.productAmounts(), "真实质量金额对账 supplier=" + supplierId);
                assertEquals(expected.values().stream().map(SupplierScoreFactsAggregationService.QualityAmount::qualifiedRaw).reduce(BigInteger.ZERO, BigInteger::add), quality.qualifiedAmountRaw());
                assertEquals(expected.values().stream().map(SupplierScoreFactsAggregationService.QualityAmount::defectiveRaw).reduce(BigInteger.ZERO, BigInteger::add), quality.defectiveAmountRaw());
                java.math.BigDecimal[] deliveryExpected = deliveryOracle(connection, supplierId, date);
                assertEquals(deliveryExpected[0].longValueExact(), delivery.dueAmountCents(), "真实到期应交金额 supplier=" + supplierId);
                assertEquals(0, deliveryExpected[1].compareTo(delivery.penaltyAmountRaw()), "真实未舍入逾期罚额 supplier=" + supplierId);
                qualitySamples += quality.validOrders(); deliverySamples += delivery.validOrders();
                dueTotal += delivery.dueAmountCents(); penaltyTotal += delivery.penaltyAmountCents();
                if (quality.validOrders() > 0) sampleSupplier = supplierId;
                System.out.printf("SCORE_FACTS supplier=%d qRaw=%s dRaw=%s qualityValid=%d qualitySkipped=%d due=%d penalty=%d penaltyRaw=%s deliveryValid=%d deliverySkipped=%d elapsedMs=%d%n",
                        supplierId, quality.qualifiedAmountRaw(), quality.defectiveAmountRaw(), quality.validOrders(), quality.skippedOrders(),
                        delivery.dueAmountCents(), delivery.penaltyAmountCents(), delivery.penaltyAmountRaw(), delivery.validOrders(), delivery.skippedOrders(), (System.nanoTime()-started)/1_000_000);
            }
            assertTrue(qualitySamples > 0); assertTrue(deliverySamples > 0);
            System.out.printf("SCORE_TOTAL suppliers=%d qualityValid=%d deliveryValid=%d dueCents=%d penaltyCents=%d%n",
                    suppliers.size(), qualitySamples, deliverySamples, dueTotal, penaltyTotal);
            try (PreparedStatement explain = connection.prepareStatement("EXPLAIN SELECT id,supplier_id,status,fully_received_at,purchase_no FROM purchase_order WHERE supplier_id=? AND deleted=0 AND status='INBOUND_DONE' AND fully_received_at>=? AND fully_received_at<? AND id>0 ORDER BY id LIMIT 200")) {
                explain.setLong(1, Objects.requireNonNull(sampleSupplier)); explain.setTimestamp(2, Timestamp.valueOf(date.minusDays(179).atStartOfDay()));
                explain.setTimestamp(3, Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
                try (ResultSet rs = explain.executeQuery()) { assertTrue(rs.next()); assertNotNull(rs.getString("key")); System.out.println("SCORE_EXPLAIN quality key=" + rs.getString("key") + " rows=" + rs.getLong("rows")); }
            }
            long orderId = firstId(connection, "SELECT id FROM purchase_order WHERE supplier_id=" + sampleSupplier + " AND status='INBOUND_DONE' AND deleted=0 ORDER BY id LIMIT 1");
            long billId = firstId(connection, "SELECT id FROM inbound_bill WHERE source_id=" + orderId + " AND source_type='PURCHASE_ORDER' AND inbound_type='PURCHASE_IN' AND status='CONFIRMED' AND deleted=0 LIMIT 1");
            long productId = firstId(connection, "SELECT supplier_product_id FROM purchase_order_item WHERE purchase_order_id=" + orderId + " LIMIT 1");
            for (String sql : List.of(
                    "SELECT id,supplier_id FROM supplier_product WHERE quote_valid_until='" + date.minusDays(1) + "' AND status=1 AND deleted=0 AND id>0 ORDER BY id LIMIT 100",
                    "SELECT id,supplier_id,status,expected_arrival_date,purchase_no FROM purchase_order WHERE supplier_id=" + sampleSupplier + " AND deleted=0 AND status IN ('APPROVED','PARTIAL_INBOUND','INBOUND_DONE') AND expected_arrival_date>='" + date.minusDays(180) + "' AND expected_arrival_date<'" + date + "' AND id>0 ORDER BY id LIMIT 200",
                    "SELECT id,purchase_order_id,supplier_product_id,quantity,inbound_qty,total_amount FROM purchase_order_item WHERE purchase_order_id=" + orderId,
                    "SELECT id,source_id,confirmed_at FROM inbound_bill WHERE source_type='PURCHASE_ORDER' AND inbound_type='PURCHASE_IN' AND status='CONFIRMED' AND deleted=0 AND source_id=" + orderId,
                    "SELECT id,inbound_bill_id,source_item_id,current_qty,qualified_qty,defective_qty,unit_price FROM inbound_bill_item WHERE inbound_bill_id=" + billId,
                    "SELECT id,supplier_id FROM supplier_product WHERE id=" + productId + " AND deleted=0")) {
                try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
                    assertTrue(rs.next());
                    System.out.printf("SCORE_EXPLAIN table=%s type=%s key=%s possible=%s rows=%d%n", rs.getString("table"), rs.getString("type"), rs.getString("key"), rs.getString("possible_keys"), rs.getLong("rows"));
                }
            }
            // 同时验证真实实体全字段映射，防止回到旧 ai_score 列后评分入口仍然报错。
            assertNotNull(session.getMapper(SupplierProductMapper.class).selectById(productId));
            try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("SELECT 'purchase_order' table_name,COUNT(*) n FROM purchase_order UNION ALL SELECT 'purchase_order_item',COUNT(*) FROM purchase_order_item UNION ALL SELECT 'inbound_bill',COUNT(*) FROM inbound_bill UNION ALL SELECT 'inbound_bill_item',COUNT(*) FROM inbound_bill_item UNION ALL SELECT 'supplier_product',COUNT(*) FROM supplier_product")) {
                while (rs.next()) System.out.println("SCORE_DATA_SIZE " + rs.getString(1) + "=" + rs.getLong(2));
            }
            session.rollback();
        }
    }

    private long firstId(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            assertTrue(rs.next(), "真实窗口应包含可解释的关联记录"); return rs.getLong(1);
        }
    }

    private Map<Long, SupplierScoreFactsAggregationService.QualityAmount> qualityOracle(Connection connection, long supplier, LocalDate date) throws SQLException {
        String sql = """
                WITH batch AS (
                  SELECT ib.source_id order_id, ibi.source_item_id item_id, SUM(ibi.current_qty) received,
                         SUM(CAST(ibi.qualified_qty AS DECIMAL(65,0))*ibi.unit_price) qraw,
                         SUM(CAST(ibi.defective_qty AS DECIMAL(65,0))*ibi.unit_price) draw,
                         SUM(IF(ibi.current_qty<=0 OR ibi.qualified_qty<0 OR ibi.defective_qty<0 OR ibi.qualified_qty+ibi.defective_qty<>ibi.current_qty OR ibi.unit_price<=0 OR ib.confirmed_at IS NULL,1,0)) bad
                  FROM inbound_bill ib JOIN inbound_bill_item ibi ON ibi.inbound_bill_id=ib.id
                  WHERE ib.source_type='PURCHASE_ORDER' AND ib.inbound_type='PURCHASE_IN' AND ib.status='CONFIRMED' AND ib.deleted=0
                  GROUP BY ib.source_id,ibi.source_item_id
                ), line_facts AS (
                  SELECT po.id order_id, poi.supplier_product_id sp_id, b.qraw,b.draw,
                         IF(COALESCE(b.received,0)<>poi.quantity OR COALESCE(b.received,0)<>poi.inbound_qty OR COALESCE(b.bad,0)>0 OR poi.quantity<=0 OR sp.id IS NULL OR sp.supplier_id<>po.supplier_id,1,0) bad
                  FROM purchase_order po JOIN purchase_order_item poi ON poi.purchase_order_id=po.id
                  LEFT JOIN batch b ON b.order_id=po.id AND b.item_id=poi.id
                  LEFT JOIN supplier_product sp ON sp.id=poi.supplier_product_id
                  WHERE po.supplier_id=? AND po.deleted=0 AND po.status='INBOUND_DONE' AND po.fully_received_at>=? AND po.fully_received_at<?
                ), valid AS (SELECT order_id FROM line_facts GROUP BY order_id HAVING SUM(bad)=0)
                SELECT sp_id,SUM(qraw),SUM(draw) FROM line_facts JOIN valid USING(order_id) GROUP BY sp_id
                """;
        Map<Long, SupplierScoreFactsAggregationService.QualityAmount> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1,supplier); statement.setTimestamp(2,Timestamp.valueOf(date.minusDays(179).atStartOfDay())); statement.setTimestamp(3,Timestamp.valueOf(date.plusDays(1).atStartOfDay()));
            try (ResultSet rs=statement.executeQuery()) { while(rs.next()) result.put(rs.getLong(1),new SupplierScoreFactsAggregationService.QualityAmount(rs.getBigDecimal(2).toBigIntegerExact(),rs.getBigDecimal(3).toBigIntegerExact())); }
        }
        return result;
    }

    private java.math.BigDecimal[] deliveryOracle(Connection connection,long supplier,LocalDate date) throws SQLException {
        String sql = """
                WITH batch AS (
                  SELECT ib.source_id order_id, ibi.source_item_id item_id, SUM(ibi.current_qty) received,
                    SUM(CAST(poi.total_amount AS DECIMAL(40,12))*ibi.current_qty/poi.quantity *
                      CASE WHEN DATEDIFF(DATE(ib.confirmed_at),po.expected_arrival_date)<=0 THEN 0 WHEN DATEDIFF(DATE(ib.confirmed_at),po.expected_arrival_date)<=3 THEN .25 WHEN DATEDIFF(DATE(ib.confirmed_at),po.expected_arrival_date)<=7 THEN .5 WHEN DATEDIFF(DATE(ib.confirmed_at),po.expected_arrival_date)<=15 THEN .75 ELSE 1 END) penalty,
                    SUM(IF(ib.confirmed_at IS NULL OR ibi.current_qty<=0 OR ibi.qualified_qty<0 OR ibi.defective_qty<0 OR ibi.qualified_qty+ibi.defective_qty<>ibi.current_qty,1,0)) bad
                  FROM inbound_bill ib JOIN inbound_bill_item ibi ON ibi.inbound_bill_id=ib.id
                  JOIN purchase_order_item poi ON poi.id=ibi.source_item_id JOIN purchase_order po ON po.id=ib.source_id
                  WHERE ib.source_type='PURCHASE_ORDER' AND ib.inbound_type='PURCHASE_IN' AND ib.status='CONFIRMED' AND ib.deleted=0
                  GROUP BY ib.source_id,ibi.source_item_id
                ), line_facts AS (
                  SELECT po.id order_id,poi.total_amount amount,
                    COALESCE(b.penalty,0)+CAST(poi.total_amount AS DECIMAL(40,12))*(poi.quantity-COALESCE(b.received,0))/poi.quantity *
                    CASE WHEN DATEDIFF(?,po.expected_arrival_date)<=3 THEN .25 WHEN DATEDIFF(?,po.expected_arrival_date)<=7 THEN .5 WHEN DATEDIFF(?,po.expected_arrival_date)<=15 THEN .75 ELSE 1 END penalty,
                    IF(COALESCE(b.received,0)<>poi.inbound_qty OR poi.inbound_qty>poi.quantity OR poi.inbound_qty<0 OR poi.quantity<=0 OR poi.total_amount<0 OR COALESCE(b.bad,0)>0 OR (po.status='INBOUND_DONE' AND COALESCE(b.received,0)<>poi.quantity),1,0) bad
                  FROM purchase_order po JOIN purchase_order_item poi ON poi.purchase_order_id=po.id
                  LEFT JOIN batch b ON b.order_id=po.id AND b.item_id=poi.id
                  WHERE po.supplier_id=? AND po.deleted=0 AND po.status IN ('APPROVED','PARTIAL_INBOUND','INBOUND_DONE') AND po.expected_arrival_date>=? AND po.expected_arrival_date<?
                ), valid AS (SELECT order_id FROM line_facts GROUP BY order_id HAVING SUM(bad)=0)
                SELECT COALESCE(SUM(amount),0),COALESCE(SUM(penalty),0) FROM line_facts JOIN valid USING(order_id)
                """;
        try(PreparedStatement statement=connection.prepareStatement(sql)) {
            for(int i=1;i<=3;i++) statement.setDate(i,java.sql.Date.valueOf(date)); statement.setLong(4,supplier);
            statement.setDate(5,java.sql.Date.valueOf(date.minusDays(180))); statement.setDate(6,java.sql.Date.valueOf(date));
            try(ResultSet rs=statement.executeQuery()) { assertTrue(rs.next()); return new java.math.BigDecimal[]{rs.getBigDecimal(1),rs.getBigDecimal(2)}; }
        }
    }
}
