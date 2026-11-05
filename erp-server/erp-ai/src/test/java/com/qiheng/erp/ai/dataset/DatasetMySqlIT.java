package com.qiheng.erp.ai.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.sql.execution.model.QueryResult;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 仅在明确提供隔离 MySQL 连接时运行，验证真实元信息读写。 */
class DatasetMySqlIT {
    @TempDir Path directory;

    /** 非 UTC 会话下验证中文列和 UTC 时间写入与回读。 */
    @Test
    void persistsUtcAndChineseColumns() throws Exception {
        String url = System.getenv("DATASET_IT_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "需要隔离的 DATASET_IT_URL");
        var driver = new DriverManagerDataSource(url, System.getenv("DATASET_IT_USER"), System.getenv("DATASET_IT_PASSWORD"));
        try (var connection = driver.getConnection()) {
            var source = new SingleConnectionDataSource(connection, true);
            JdbcTemplate jdbc = new JdbcTemplate(source);
            jdbc.execute("SET SESSION time_zone = '+08:00'");
            assertEquals("+08:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
            jdbc.execute(Files.readString(Path.of("..", "..", "docs", "database", "sql", "029_ai_dataset.sql")));
            ensureDescriptionColumn(jdbc);
            ObjectMapper mapper = new ObjectMapper();
            DatasetManager manager = new DatasetManager(DatasetMapperTestFactory.create(source, mapper), record -> {},
                    mapper, directory.toString(), 3600);
            String runId = "utc_" + UUID.randomUUID();
            var request = new DatasetRequest(runId, 1001, 2001, "中文库存", "SELECT amount FROM stock", 1);
            var columns = List.of(new SqlQueryColumn("amount", "库存数量", Types.DECIMAL, "DECIMAL", false));
            var result = new QueryResult(columns, List.of(List.of(new BigDecimal("12.50"))), 1, true, 1, 1, "hash");
            Instant before = Instant.now();
            try {
                DatasetRecord staged = manager.stage(request, result);
                DatasetRecord loaded = manager.get(staged.datasetId(), 1001, 2001);
                assertEquals("库存数量", loaded.columns().getFirst().label());
                LocalDateTime created = jdbc.queryForObject("SELECT created_at FROM ai_dataset WHERE dataset_id=?",
                        LocalDateTime.class, staged.datasetId());
                LocalDateTime expires = jdbc.queryForObject("SELECT expires_at FROM ai_dataset WHERE dataset_id=?",
                        LocalDateTime.class, staged.datasetId());
                assertNotNull(created);
                assertNotNull(expires);
                Instant createdUtc = created.toInstant(ZoneOffset.UTC);
                assertTrue(!createdUtc.isBefore(before.minusSeconds(2)) && !createdUtc.isAfter(Instant.now().plusSeconds(2)));
                assertTrue(Math.abs(Duration.between(created, expires).toSeconds() - 3600) <= 2);
                assertEquals(expires.toInstant(ZoneOffset.UTC), loaded.expiresAt());
            } finally {
                jdbc.update("DELETE FROM ai_dataset WHERE sql_run_id = ?", runId);
            }
        }
    }

    @Test
    void persistsAndRecoversDatasetWithoutCrossOwnerAccess() throws Exception {
        String url = System.getenv("DATASET_IT_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "需要隔离的 DATASET_IT_URL");
        var source = new DriverManagerDataSource(url, System.getenv("DATASET_IT_USER"), System.getenv("DATASET_IT_PASSWORD"));
        JdbcTemplate jdbc = new JdbcTemplate(source);
        Path migration = Path.of("..", "..", "docs", "database", "sql", "029_ai_dataset.sql");
        jdbc.execute(Files.readString(migration));
        ensureDescriptionColumn(jdbc);
        ObjectMapper mapper = new ObjectMapper();
        DatasetManager manager = new DatasetManager(DatasetMapperTestFactory.create(source, mapper), record -> {}, mapper, directory.toString(), 3600);
        String runId = "it_" + UUID.randomUUID();
        DatasetRequest req = new DatasetRequest(runId, 1001, 2001, "库存", "SELECT amount FROM stock", 1);
        var columns = List.of(new SqlQueryColumn("amount", "amount", Types.DECIMAL, "DECIMAL", false));
        var query = new QueryResult(columns, List.of(List.of(new BigDecimal("123.123456789"))), 1, false, 1, 1, "hash", "PAGE_LIMIT");
        try {
            DatasetRecord staged = manager.stage(req, query);
            assertEquals("STAGED", staged.status());
            assertEquals(staged.datasetId(), manager.stage(req, query).datasetId());
            assertThrows(IllegalStateException.class, () -> manager.read(staged.datasetId(), 1001, 2001));
            assertThrows(IllegalArgumentException.class, () -> manager.get(staged.datasetId(), 1002, 2001));
            assertThrows(IllegalArgumentException.class, () -> manager.get(staged.datasetId(), 1001, 2002));
            SqlDataDescription description = new SqlDataDescription("库存", "每个商品一行",
                    List.of(new SqlDataField(0, "amount", "库存数量")), "");
            DatasetRecord ready = manager.approve(staged.datasetId(), 1001, 2001, description);
            assertEquals("READY", ready.status());
            assertFalse(ready.completed());
            assertEquals("PAGE_LIMIT", ready.truncationReason());
            assertEquals(description, ready.dataDescription());
            assertNotNull(ready.expiresAt());
            assertEquals(description, manager.approve(ready.datasetId(), 1001, 2001, null).dataDescription());
            assertEquals(ready.datasetId(), manager.findReady(runId, 1001, 2001).orElseThrow().datasetId());
            assertEquals("PAGE_LIMIT", manager.findReady(runId, 1001, 2001).orElseThrow().truncationReason());
            assertTrue(manager.readPage(ready.datasetId(), 1001, 2001, 0, 1).completenessMessage().contains("分页上限"));
            assertEquals(new BigDecimal("123.123456789"), manager.read(ready.datasetId(), 1001, 2001).getFirst().getFirst());
            manager.discard(ready.datasetId(), 1001, 2001);
            assertEquals(1, manager.read(ready.datasetId(), 1001, 2001).size());
        } finally {
            jdbc.update("DELETE FROM ai_dataset WHERE sql_run_id = ?", runId);
        }
    }

    /** 测试库可能已运行过迁移，只在缺列时执行一次。 */
    static void ensureDescriptionColumn(JdbcTemplate jdbc) throws Exception {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='ai_dataset' AND column_name='data_description_json'", Integer.class);
        if (count != null && count == 0) {
            jdbc.execute(Files.readString(Path.of("..", "sql", "030_ai_dataset_description.sql")));
        }
    }
}
