package com.qiheng.erp.purchase.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.github.yulichang.injector.MPJSqlInjector;
import com.github.yulichang.interceptor.MPJInterceptor;
import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;
import com.qiheng.erp.common.util.BillNoGenerator;
import com.qiheng.erp.common.util.CodeNoGenerator;
import com.qiheng.erp.product.domain.dto.ProductReferencePriceDto;
import com.qiheng.erp.product.mapper.ProductCategoryMapper;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.product.service.IProductService;
import com.qiheng.erp.product.service.impl.ProductServiceImpl;
import com.qiheng.erp.purchase.domain.supplier.dto.SupplierServiceScoreDto;
import com.qiheng.erp.purchase.domain.supplierproduct.dto.SupplierProductQuoteDto;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreRecalcContext;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import com.qiheng.erp.purchase.mapper.*;
import com.qiheng.erp.purchase.mq.SupplierScoreMqFixture;
import com.qiheng.erp.purchase.service.ISupplierProductService;
import com.qiheng.erp.purchase.service.ISupplierService;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import com.qiheng.erp.purchase.service.scoring.*;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.mapper.InboundBillItemMapper;
import com.qiheng.erp.warehouse.mapper.InboundBillMapper;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 显式启用的真实 MySQL、Redis 并发回归，不启动 MQ，也不修改已有业务记录。
 * 每项用例经过真实业务 Service 与事务代理，闸门仅控制真实行锁调用前后的时序。
 * 使用唯一夹具并在工作线程退出后精确清理；测试不构成生产环境绝无死锁的保证。
 */
@EnabledIfSystemProperty(named = "score.concurrent.it", matches = "true")
class SupplierScoreConcurrencyIT {
    private AnnotationConfigApplicationContext context;
    private SupplierScoreMqFixture fixture;
    private SupplierScoreMqFixture.Sample sample;
    private JdbcTemplate jdbc;
    private SupplierScoreRecalculateService recalculate;
    private GateProbe probe;
    private ExecutorService workers;
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));

    @BeforeEach
    void createIsolatedApplicationAndFixture() {
        context = new AnnotationConfigApplicationContext(RealConfiguration.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        fixture = new SupplierScoreMqFixture(jdbc, UUID.randomUUID().toString().replace("-", "").substring(0, 16));
        sample = fixture.create().getFirst();
        recalculate = context.getBean(SupplierScoreRecalculateService.class);
        probe = context.getBean(GateProbe.class);
        workers = Executors.newFixedThreadPool(2);
        assertTrue(AopUtils.isAopProxy(recalculate), "评分入口必须经过真实事务代理");
        assertTrue(AopUtils.isAopProxy(context.getBean(ISupplierService.class)));
        assertTrue(AopUtils.isAopProxy(context.getBean(ISupplierProductService.class)));
        assertTrue(AopUtils.isAopProxy(context.getBean(IProductService.class)));
        assertEquals(LocalCacheScope.SESSION, context.getBean(SqlSessionFactory.class).getConfiguration().getLocalCacheScope(),
                "保留项目默认 SESSION 缓存，不能通过 STATEMENT 配置掩盖锁后重读问题");
        Map<String, Object> explain = jdbc.queryForMap(
                "EXPLAIN SELECT id FROM supplier WHERE id=? AND deleted=0 FOR UPDATE", sample.supplierId());
        assertEquals("PRIMARY", explain.get("key"));
        assertTrue(((Number) explain.get("rows")).longValue() <= 1, "供应商互斥门应当是主键单行查询");
        facts();
        assertEquals(6000, supplierScore("quality_score"));
        assertEquals(7500, supplierScore("delivery_score"));
        assertEquals(8889, supplierScore("price_score"));
        assertEquals(7517, supplierScore("overall_score"));
    }

    @AfterEach
    void cleanupOnlyOwnedDataAfterWorkersExit() throws InterruptedException {
        if (probe != null) probe.clear();
        try {
            if (workers != null) {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "禁止在工作事务仍运行时删除夹具");
            }
            if (context != null && fixture != null) {
                for (var owned : fixture.samples()) {
                    assertTrue(jdbc.queryForList("SELECT DISTINCT batch_no FROM supplier_score_change_log WHERE supplier_id=?",
                            String.class, owned.supplierId()).stream().allMatch(no -> no.matches("SC\\d{13}")),
                            "真实业务服务、报价、参考价、每日重算的日志必须使用统一批次格式");
                }
                var client = context.getBean(RedissonClient.class);
                var redis = context.getBean(StringRedisTemplate.class);
                for (var owned : fixture.samples()) {
                    var lock = client.getLock(SupplierScoreRedisKeys.lockKey(owned.supplierId()));
                    assertTrue(lock.tryLock(10, TimeUnit.SECONDS), "清理前必须确认没有本测试的评分在途");
                    try {
                        redis.delete(List.of(SupplierScoreRedisKeys.qualityAmountKey(owned.supplierId()),
                                SupplierScoreRedisKeys.pendingKey(owned.supplierId())));
                    } finally {
                        lock.unlock();
                    }
                }
                fixture.cleanup();
                for (var owned : fixture.samples()) {
                    assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM supplier WHERE id=?", Integer.class, owned.supplierId()));
                    assertFalse(Boolean.TRUE.equals(redis.hasKey(SupplierScoreRedisKeys.qualityAmountKey(owned.supplierId()))));
                }
            }
        } finally {
            if (context != null) context.close();
        }
    }

    @Test
    void referencePriceAndQuoteShouldNotLeaveOldSnapshotPrice() throws Exception {
        CountDownLatch referenceWritten = new CountDownLatch(1);
        CountDownLatch quoteCommitted = new CountDownLatch(1);
        probe.before = (thread, id) -> {
            if (thread.equals("reference")) {
                referenceWritten.countDown();
                await(quoteCommitted);
            }
        };
        Future<?> reference = run("reference", () -> {
            ProductReferencePriceDto dto = new ProductReferencePriceDto();
            dto.setReferencePurchasePrice(new BigDecimal("120.00"));
            context.getBean(IProductService.class).updateReferencePrice(sample.productIds().getFirst(), dto);
        });
        await(referenceWritten);
        Future<?> quote = run("quote", () -> {
            try { quote(new BigDecimal("150.00")); }
            finally { quoteCommitted.countDown(); }
        });
        quote.get(20, TimeUnit.SECONDS);
        reference.get(20, TimeUnit.SECONDS);
        assertEquals(new BigDecimal("120.00"), jdbc.queryForObject(
                "SELECT reference_purchase_price FROM product WHERE id=?", BigDecimal.class, sample.productIds().getFirst()));
        assertEquals(15000L, jdbc.queryForObject("SELECT quoted_purchase_price FROM supplier_product WHERE id=?",
                Long.class, sample.supplierProductIds().getFirst()));
        assertEquals(8000, productScore(0, "price_score"), "不能残留旧参考价100下的6667分");
        assertEquals(8222, supplierScore("price_score"));
        assertEquals(7850, productScore(0, "recommend_score"));
        assertEquals(7317, supplierScore("overall_score"));
    }

    @Test
    void factsShouldCreateSnapshotOnlyAfterWaitingSupplierGate() throws Exception {
        CountDownLatch writerLocked = new CountDownLatch(1);
        CountDownLatch factsAtGate = new CountDownLatch(1);
        probe.before = (thread, id) -> { if (thread.equals("facts")) factsAtGate.countDown(); };
        Future<?> writer = run("writer", () -> {
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.executeWithoutResult(status -> {
                context.getBean(SupplierMapper.class).lockByIdForUpdate(sample.supplierId());
                writerLocked.countDown();
                await(factsAtGate);
                // 在重算开始等待后才改事实，验证首次一致性读没有提前建立旧快照。
                jdbc.update("UPDATE inbound_bill_item SET qualified_qty=1000,defective_qty=0 WHERE id=?",
                        sample.inboundItemIds().getFirst());
            });
        });
        await(writerLocked);
        Future<?> facts = run("facts", this::facts);
        writer.get(20, TimeUnit.SECONDS);
        facts.get(20, TimeUnit.SECONDS);
        assertEquals(6667, supplierScore("quality_score"));
        assertEquals(10000, productScore(0, "quality_score"));
        assertEquals(9050, productScore(0, "recommend_score"));
        assertEquals(7717, supplierScore("overall_score"));
        var cached = context.getBean(SupplierQualityAmountCacheService.class).read(sample.supplierId(), today);
        assertNotNull(cached);
        assertEquals(BigInteger.valueOf(20000000), cached.qualifiedRaw());
        assertEquals(BigInteger.valueOf(10000000), cached.defectiveRaw());
    }

    @Test
    void expiryShouldNotClearQuoteRenewedWhileWaitingSupplierGate() throws Exception {
        jdbc.update("UPDATE supplier_product SET quote_valid_until=? WHERE id=?", today.minusDays(1), sample.supplierProductIds().getFirst());
        CountDownLatch renewalLocked = new CountDownLatch(1);
        CountDownLatch expiryAtGate = new CountDownLatch(1);
        probe.before = (thread, id) -> { if (thread.equals("expiry")) expiryAtGate.countDown(); };
        probe.after = (thread, id) -> {
            if (thread.equals("renewal")) { renewalLocked.countDown(); await(expiryAtGate); }
        };
        Future<?> renewal = run("renewal", () -> quote(new BigDecimal("100.00")));
        await(renewalLocked);
        Future<?> expiry = run("expiry", () -> withOuterRedis(() -> recalculate.recalcPricesForSupplier(sample.supplierId())));
        renewal.get(20, TimeUnit.SECONDS);
        expiry.get(20, TimeUnit.SECONDS);
        assertEquals(10000, productScore(0, "price_score"));
        assertEquals(8450, productScore(0, "recommend_score"));
        assertEquals(8889, supplierScore("price_score"));
        assertEquals("READY", jdbc.queryForObject("SELECT score_status FROM supplier WHERE id=?", String.class, sample.supplierId()));
    }

    @Test
    void quoteShouldRereadCommittedVersionInsteadOfSessionCachedRelation() throws Exception {
        int previousVersion = jdbc.queryForObject("SELECT version FROM supplier_product WHERE id=?", Integer.class, sample.supplierProductIds().getFirst());
        CountDownLatch writerLocked = new CountDownLatch(1);
        CountDownLatch quoteAtGate = new CountDownLatch(1);
        AtomicInteger observedVersion = new AtomicInteger(-1);
        probe.before = (thread, id) -> { if (thread.equals("quote")) quoteAtGate.countDown(); };
        probe.after = (thread, id) -> {
            if (thread.equals("quote")) {
                // 仍在报价事务同一 SqlSession 中读取，专门验证 FOR UPDATE 清掉锁前定位查询的一级缓存。
                observedVersion.set(context.getBean(SupplierProductMapper.class)
                        .selectById(sample.supplierProductIds().getFirst()).getVersion());
            }
        };
        Future<?> writer = run("writer", () -> {
            var tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            tx.executeWithoutResult(status -> {
                context.getBean(SupplierMapper.class).lockByIdForUpdate(sample.supplierId());
                writerLocked.countDown();
                await(quoteAtGate);
                jdbc.update("UPDATE supplier_product SET version=version+1 WHERE id=?", sample.supplierProductIds().getFirst());
            });
        });
        await(writerLocked);
        Future<?> quote = run("quote", () -> quote(new BigDecimal("150.00")));
        writer.get(20, TimeUnit.SECONDS);
        ExecutionException failure = assertThrows(ExecutionException.class, () -> quote.get(20, TimeUnit.SECONDS));
        assertTrue(failure.getCause().getMessage().contains("供货关系数据已变化"));
        assertEquals(previousVersion + 1, observedVersion.get(), "锁后不能沿用 SESSION 中缓存的锁前供货关系");
        assertEquals(10000L, jdbc.queryForObject("SELECT quoted_purchase_price FROM supplier_product WHERE id=?",
                Long.class, sample.supplierProductIds().getFirst()), "陈旧请求必须整体拒绝，不能覆盖当前报价");
        assertEquals(10000, productScore(0, "price_score"));
    }

    @Test
    void serviceChangeAndFactsShouldPersistScoresUsingCommittedService() throws Exception {
        CountDownLatch serviceLocked = new CountDownLatch(1);
        CountDownLatch factsAtGate = new CountDownLatch(1);
        probe.before = (thread, id) -> { if (thread.equals("facts")) factsAtGate.countDown(); };
        probe.after = (thread, id) -> {
            if (thread.equals("service")) { serviceLocked.countDown(); await(factsAtGate); }
        };
        Future<?> service = run("service", () -> service(new BigDecimal("95.00")));
        await(serviceLocked);
        Future<?> facts = run("facts", this::facts);
        service.get(20, TimeUnit.SECONDS);
        facts.get(20, TimeUnit.SECONDS);
        assertEquals(9500, supplierScore("service_score"));
        assertEquals(7667, supplierScore("overall_score"));
        assertEquals(8600, productScore(0, "recommend_score"));
        assertEquals(7200, productScore(1, "recommend_score"));
    }

    @Test
    void logFailureShouldRollbackInputScoresVersionsAndReleaseSupplierGate() throws Exception {
        Map<String, Object> beforeSupplier = jdbc.queryForMap("SELECT service_score,overall_score,version FROM supplier WHERE id=?", sample.supplierId());
        List<Map<String, Object>> beforeProducts = jdbc.queryForList("SELECT id,recommend_score,version FROM supplier_product WHERE supplier_id=? ORDER BY id", sample.supplierId());
        int logs = jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?", Integer.class, sample.supplierId());
        context.getBean(FailureSwitch.class).failNextBatch.set(true);
        Future<?> failed = run("failure", () -> service(new BigDecimal("95.00")));
        ExecutionException error = assertThrows(ExecutionException.class, () -> failed.get(20, TimeUnit.SECONDS));
        assertTrue(error.getCause().getMessage().contains("测试批次号故障"));
        assertEquals(beforeSupplier, jdbc.queryForMap("SELECT service_score,overall_score,version FROM supplier WHERE id=?", sample.supplierId()));
        assertEquals(beforeProducts, jdbc.queryForList("SELECT id,recommend_score,version FROM supplier_product WHERE supplier_id=? ORDER BY id", sample.supplierId()));
        assertEquals(logs, jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?", Integer.class, sample.supplierId()));
        run("retry", () -> service(new BigDecimal("95.00"))).get(20, TimeUnit.SECONDS);
        assertEquals(9500, supplierScore("service_score"));
        assertEquals(7667, supplierScore("overall_score"));
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM supplier_score_change_log WHERE supplier_id=?", Integer.class, sample.supplierId()) > logs);
    }

    /** 在调用事务代理之前取得真实 Redis 外层锁，遵循正式消费者的锁顺序。 */
    private void facts() {
        withOuterRedis(() -> recalculate.recalcFactsForSupplier(ScoreRecalcContext.builder()
                .supplierId(sample.supplierId()).triggerType(TriggerType.DAILY_TRIGGER)
                .operatorType("SYSTEM").operatorName("并发集成测试").build(), today));
    }

    /** 每次查询当前客户端版本；并发场景由闸门控制在该版本提交前后执行。 */
    private void quote(BigDecimal price) {
        SupplierProductQuoteDto dto = new SupplierProductQuoteDto();
        dto.setVersion(jdbc.queryForObject("SELECT version FROM supplier_product WHERE id=?", Integer.class, sample.supplierProductIds().getFirst()));
        dto.setQuotedPurchasePrice(price);
        dto.setQuoteValidUntil(today.plusDays(30));
        dto.setReason("并发测试报价续期");
        context.getBean(ISupplierProductService.class).updateQuote(sample.supplierProductIds().getFirst(), dto);
    }

    /** 通过实际业务入口同时更新服务分和衍生评分，不直接调用同步重算绕过业务行锁。 */
    private void service(BigDecimal score) {
        SupplierServiceScoreDto dto = new SupplierServiceScoreDto();
        dto.setVersion(jdbc.queryForObject("SELECT version FROM supplier WHERE id=?", Integer.class, sample.supplierId()));
        dto.setServiceScore(score);
        dto.setReason("并发测试服务分调整");
        context.getBean(ISupplierService.class).updateServiceScore(sample.supplierId(), dto);
    }

    /** 登录上下文只在当前工作线程模拟，业务数据、SQL、事务与评分计算都是真实实现。 */
    private Future<?> run(String name, Runnable task) {
        return workers.submit(() -> {
            Thread.currentThread().setName(name);
            LoginUser user = new LoginUser();
            user.setUserId(1L); user.setUsername("score-concurrency-it"); user.setRealName("评分并发测试");
            try (var mocked = mockStatic(UserContext.class)) {
                mocked.when(UserContext::requireCurrentUser).thenReturn(user);
                mocked.when(UserContext::getCurrentUser).thenReturn(user);
                mocked.when(UserContext::getUserId).thenReturn(user.getUserId());
                task.run();
            }
        });
    }

    /** 有界等待真实外层锁，失败不能退化为无锁执行。 */
    private void withOuterRedis(Runnable task) {
        var lock = context.getBean(RedissonClient.class).getLock(SupplierScoreRedisKeys.lockKey(sample.supplierId()));
        try {
            assertTrue(lock.tryLock(10, TimeUnit.SECONDS));
            try { task.run(); } finally { lock.unlock(); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("测试等待供应商锁被中断", e);
        }
    }

    /** 使用独立常量核对真实落库分数，不复用被测计算器生成期望。 */
    private int supplierScore(String column) { return jdbc.queryForObject("SELECT " + column + " FROM supplier WHERE id=?", Integer.class, sample.supplierId()); }

    /** 仅允许用例内部固定评分列，不接受外部输入作为 SQL 标识符。 */
    private int productScore(int index, String column) { return jdbc.queryForObject("SELECT " + column + " FROM supplier_product WHERE id=?", Integer.class, sample.supplierProductIds().get(index)); }

    /** 闸门必须有超时，避免测试错误形成永久等待。 */
    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(15, TimeUnit.SECONDS), "并发测试闸门超时"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("并发测试被中断", e); }
    }

    /** 只控制真实锁查询前后时序，不替代 MySQL 行锁。 */
    static class GateProbe {
        volatile BiConsumer<String, Long> before = (thread, id) -> { };
        volatile BiConsumer<String, Long> after = (thread, id) -> { };
        void clear() { before = (thread, id) -> { }; after = (thread, id) -> { }; }
    }

    /** 故障只影响当前测试应用的下一次批次生成，不写共享 Redis 单号序列。 */
    static class FailureSwitch { final AtomicBoolean failNextBatch = new AtomicBoolean(); }

    /** 最小真实应用，排除无关 MQ、Web 和任务，保留默认 MyBatis 会话缓存与实际业务事务。 */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import({SupplierServiceImpl.class, SupplierProductServiceImpl.class, ProductServiceImpl.class,
            ProductReferencePriceChangeHandlerImpl.class, SupplierScoreRecalculateServiceImpl.class,
            SupplierScoreChangeLogServiceImpl.class, SupplierScoreFactsAggregationService.class,
            ScoreFactsQueryService.class, SupplierQualityAmountCacheService.class, QualityScoreCalculator.class,
            DeliveryScoreCalculator.class, PriceScoreCalculator.class, AggregateScoreCalculator.class})
    static class RealConfiguration {
        @Bean com.fasterxml.jackson.databind.ObjectMapper objectMapper() {
            return new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        }
        @Bean DataSource dataSource() {
            String password = System.getenv("ERP_IT_JDBC_PASSWORD");
            if (password == null || password.isBlank()) {
                YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
                yaml.setResources(new FileSystemResource("../erp-admin/src/main/resources/application-dev.yml"));
                password = Objects.requireNonNull(yaml.getObject()).getProperty("spring.datasource.password");
                if (password != null && password.startsWith("${") && password.endsWith("}")) {
                    String placeholder = password.substring(2, password.length() - 1);
                    int separator = placeholder.indexOf(':');
                    String environment = System.getenv(separator < 0 ? placeholder : placeholder.substring(0, separator));
                    password = environment != null ? environment : separator < 0 ? null : placeholder.substring(separator + 1);
                }
            }
            assertNotNull(password, "请通过本机环境变量配置测试数据库密码");
            DriverManagerDataSource source = new DriverManagerDataSource();
            source.setDriverClassName("com.mysql.cj.jdbc.Driver");
            source.setUrl(System.getProperty("score.it.url", "jdbc:mysql://localhost:13307/erp?serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true"));
            source.setUsername(System.getProperty("score.it.user", "root"));
            source.setPassword(password);
            return source;
        }
        @Bean PlatformTransactionManager transactionManager(DataSource source) { return new DataSourceTransactionManager(source); }
        @Bean GateProbe gateProbe() { return new GateProbe(); }
        @Bean FailureSwitch failureSwitch() { return new FailureSwitch(); }
        @Bean CodeNoGenerator codeNoGenerator() { return mock(CodeNoGenerator.class); }
        @Bean ReturnOrderMapper returnOrderMapper() { return mock(ReturnOrderMapper.class); }
        @Bean BillNoGenerator billNoGenerator(FailureSwitch failures, StringRedisTemplate redis, RedissonClient client) {
            // 使用真实统一编号器，同时保留编号阶段故障注入，验证事务回滚而非伪造批次号。
            BillNoGenerator generator = spy(new BillNoGenerator(redis, client));
            doAnswer(invocation -> {
                if (failures.failNextBatch.compareAndSet(true, false)) throw new IllegalStateException("测试批次号故障");
                return invocation.callRealMethod();
            }).when(generator).nextNo(eq("SC"), any());
            return generator;
        }
        @Bean(destroyMethod = "shutdown") RedissonClient redissonClient() {
            Config config = new Config();
            config.useSingleServer().setAddress(System.getProperty("score.it.redis", "redis://localhost:6379"));
            return Redisson.create(config);
        }
        @Bean StringRedisTemplate redis(RedissonClient client) { return new StringRedisTemplate(new RedissonConnectionFactory(client)); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource source) throws Exception {
            MybatisConfiguration configuration = new MybatisConfiguration();
            configuration.setMapUnderscoreToCamelCase(true); configuration.setLogImpl(NoLoggingImpl.class);
            GlobalConfig global = new GlobalConfig(); global.setDbConfig(new GlobalConfig.DbConfig());
            global.setSqlInjector(new MPJSqlInjector());
            MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
            factory.setDataSource(source); factory.setConfiguration(configuration); factory.setGlobalConfig(global);
            factory.setPlugins(new MPJInterceptor());
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mapper/*.xml"));
            SqlSessionFactory sessions = factory.getObject();
            for (Class<?> mapper : List.of(SupplierMapper.class, SupplierProductMapper.class, ProductMapper.class,
                    ProductCategoryMapper.class, PurchaseOrderMapper.class, PurchaseOrderItemMapper.class,
                    InboundBillMapper.class, InboundBillItemMapper.class, SupplierScoreChangeLogMapper.class)) {
                if (!configuration.hasMapper(mapper)) configuration.addMapper(mapper);
            }
            return sessions;
        }
        @Bean SqlSessionTemplate session(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean SupplierMapper supplierMapper(SqlSessionTemplate s, GateProbe probe) {
            SupplierMapper real = s.getMapper(SupplierMapper.class);
            SupplierMapper controlled = mock(SupplierMapper.class, delegatesTo(real));
            doAnswer(invocation -> {
                Long id = invocation.getArgument(0);
                String thread = Thread.currentThread().getName();
                probe.before.accept(thread, id);
                Long locked = real.lockByIdForUpdate(id);
                probe.after.accept(thread, id);
                return locked;
            }).when(controlled).lockByIdForUpdate(anyLong());
            return controlled;
        }
        @Bean SupplierProductMapper supplierProductMapper(SqlSessionTemplate s) { return s.getMapper(SupplierProductMapper.class); }
        @Bean ProductMapper productMapper(SqlSessionTemplate s) { return s.getMapper(ProductMapper.class); }
        @Bean ProductCategoryMapper productCategoryMapper(SqlSessionTemplate s) { return s.getMapper(ProductCategoryMapper.class); }
        @Bean PurchaseOrderMapper orderMapper(SqlSessionTemplate s) { return s.getMapper(PurchaseOrderMapper.class); }
        @Bean PurchaseOrderItemMapper orderItemMapper(SqlSessionTemplate s) { return s.getMapper(PurchaseOrderItemMapper.class); }
        @Bean InboundBillMapper inboundMapper(SqlSessionTemplate s) { return s.getMapper(InboundBillMapper.class); }
        @Bean InboundBillItemMapper inboundItemMapper(SqlSessionTemplate s) { return s.getMapper(InboundBillItemMapper.class); }
        @Bean SupplierScoreChangeLogMapper logs(SqlSessionTemplate s) { return s.getMapper(SupplierScoreChangeLogMapper.class); }
    }
}
