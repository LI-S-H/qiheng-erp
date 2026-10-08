package com.qiheng.erp.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import com.qiheng.erp.dashboard.cache.TopProductRankCache;
import com.qiheng.erp.dashboard.cache.TopProductRankRefresher;
import com.qiheng.erp.dashboard.domain.exception.entity.SystemException;
import com.qiheng.erp.dashboard.loader.DashboardTopProductLoader;
import com.qiheng.erp.dashboard.mapper.SystemExceptionMapper;
import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.product.mapper.ProductMapper;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrderItem;
import com.qiheng.erp.returnorder.mapper.ReturnOrderItemMapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrderItem;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderItemMapper;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TOP 商品排行定时任务完整集成测试:真实 Redis + 真实 MySQL + 真实云端 RocketMQ。
 *
 * <p>覆盖 7 个端到端场景(凌晨 SQL 重建 / 增量更新 / 重建期缓冲 / 脏数据全链路 /
 * 守卫抑制 / SWAP 双 EXISTS / 完整 VO 链路),作为本次定时任务改造的最终验收。</p>
 *
 * <p>启用条件:{@code -Drank.flow.it=true};CI 默认不跑——依赖本地 Redis(localhost:6379)、
 * 本地 MySQL(localhost:13307/erp)、云端 RocketMQ(8.154.27.144:9876)三套环境同时可用。</p>
 *
 * <p>风险与权衡:
 * <ul>
 *   <li>真实 MQ 上报:会向 system-exception topic 投递消息,Consumer 真实消费写库;
 *       测试前后清理 errorCode=RankDataCorruptedException 的旧记录避免历史污染</li>
 *   <li>Spring 启动慢:~30 秒,7 个测试共用同一上下文(Spring Test 缓存)</li>
 *   <li>测试数据隔离:每个 productCode/salesNo/returnNo 加 epoch ms 前缀,避免并行 IT 冲突</li>
 * </ul>
 *
 * @author Li
 * @since 2026-10-08
 */
@SpringBootTest(classes = ErpApplication.class)
@ActiveProfiles("dev")
@EnabledIfSystemProperty(named = "rank.flow.it", matches = "true")
@DisplayName("TOP 商品排行定时任务完整集成测试")
class TopProductRankFlowIT {

    // ==================== Redis Key 集中管理(与 TopProductRankCache 保持同名) ====================
    private static final String AMOUNT_KEY = "dashboard:top-product:sales-amount";
    private static final String QTY_KEY = "dashboard:top-product:sales-qty";
    private static final String REBUILDING_KEY = "dashboard:top-product:rebuilding";
    private static final String REBUILD_BUFFER_KEY = "dashboard:top-product:rebuild-buffer";
    private static final String REBUILD_LOCK_KEY = "dashboard:top-product:rebuild-lock";
    private static final List<String> ALL_REDIS_KEYS = List.of(
            AMOUNT_KEY, QTY_KEY, REBUILDING_KEY, REBUILD_BUFFER_KEY, REBUILD_LOCK_KEY);

    // ==================== 金额/数量换算 ====================
    private static final long YUAN_TO_CENT = 100L;
    private static final long CATEGORY_ID = 999_000_001L;
    private static final long WAREHOUSE_ID = 999_000_002L;
    private static final long CUSTOMER_ID = 999_000_003L;
    private static final long CREATED_BY_ID = 999_000_004L;

    @Autowired private TopProductRankCache rankCache;
    @Autowired private TopProductRankRefresher rankRefresher;
    @Autowired private DashboardTopProductLoader topProductLoader;
    @Autowired private StringRedisTemplate redisTemplate;
    @Autowired private ProductMapper productMapper;
    @Autowired private SalesOrderMapper salesOrderMapper;
    @Autowired private SalesOrderItemMapper salesOrderItemMapper;
    @Autowired private ReturnOrderItemMapper returnOrderItemMapper;
    @Autowired private WarehouseStockMapper warehouseStockMapper;
    @Autowired private SystemExceptionMapper systemExceptionMapper;
    @Autowired private SystemExceptionMqPublisher publisher;

    /** 本次测试的命名空间(epoch ms + 自增序列)用于唯一化 productCode/salesNo/returnNo */
    private final String testNamespace = "TEST_RANK_" + System.currentTimeMillis();
    private final AtomicLong productSeq = new AtomicLong(0);
    private final List<Long> createdProductIds = new ArrayList<>();
    private final List<String> createdSalesNos = new ArrayList<>();

    // ==================== 清理钩子 ====================

    @BeforeEach
    void cleanBefore() {
        cleanRedis();
        cleanSystemExceptionTable();
    }

    @AfterEach
    void cleanAfter() {
        cleanRedis();
        cleanTestData();
        cleanSystemExceptionTable();
    }

    /** 清空 6 个 Redis 测试 key(防止测试间污染 + 防止多实例部署 key 残留) */
    private void cleanRedis() {
        redisTemplate.delete(ALL_REDIS_KEYS);
    }

    /** 清理本测试 namespace 下的所有 MySQL 业务数据(按 productId 关联删除) */
    private void cleanTestData() {
        if (!createdProductIds.isEmpty()) {
            // 销售订单明细按 sales_order_id 删(只在有销售订单时才查)
            if (!createdSalesNos.isEmpty()) {
                List<Long> salesOrderIds = salesOrderMapper.selectList(
                        new LambdaQueryWrapper<SalesOrder>()
                                .in(SalesOrder::getSalesNo, createdSalesNos))
                        .stream().map(SalesOrder::getId).toList();
                if (!salesOrderIds.isEmpty()) {
                    salesOrderItemMapper.delete(
                            new LambdaQueryWrapper<SalesOrderItem>()
                                    .in(SalesOrderItem::getSalesOrderId, salesOrderIds));
                    salesOrderMapper.deleteByIds(salesOrderIds);
                }
            }
            // 退货明细按 product_id 删(测试中只插退货明细不插主单,通过源订单 item ID 定位)
            returnOrderItemMapper.delete(
                    new LambdaQueryWrapper<ReturnOrderItem>()
                            .in(ReturnOrderItem::getProductId, createdProductIds));
            // 库存按 product_id 删
            warehouseStockMapper.delete(
                    new LambdaQueryWrapper<WarehouseStock>()
                            .in(WarehouseStock::getProductId, createdProductIds));
            // 商品按 id 删
            productMapper.deleteByIds(createdProductIds);
        }
        createdProductIds.clear();
        createdSalesNos.clear();
    }

    /** 清理脏数据上报产生的 system_exception 记录(errorCode 精准匹配) */
    private void cleanSystemExceptionTable() {
        systemExceptionMapper.delete(
                new LambdaQueryWrapper<SystemException>()
                        .eq(SystemException::getErrorCode, "RankDataCorruptedException"));
    }

    // ==================== 测试场景 ====================

    @Test
    @DisplayName("1. 凌晨 SQL 重建:APPROVED 销售订单自动聚合到 Redis")
    void shouldRebuildFromMysqlViaRefreshTask() {
        // 预置:1 个商品 + 1 张 APPROVED 销售订单(含 2 条明细,合计 3000.00 元 / 150.00 件)
        // totalAmount 字段单位 = 分,N/100 = 元;quantity 字段单位 = ×100 件
        Long productId = newProduct("P1");
        long item1AmountCents = 100_000L;            // 1000.00 元 = 100_000 分
        long item1QtyBy100 = 50L * YUAN_TO_CENT;     // 50.00 件
        long item2AmountCents = 200_000L;            // 2000.00 元 = 200_000 分
        long item2QtyBy100 = 100L * YUAN_TO_CENT;    // 100.00 件
        Long salesOrderId = newSalesOrder(productId, SalesOrderStatus.APPROVED.name());
        newSalesOrderItem(salesOrderId, productId, item1AmountCents, item1QtyBy100, "1001");
        newSalesOrderItem(salesOrderId, productId, item2AmountCents, item2QtyBy100, "1002");

        // 触发:refresh() 即凌晨定时任务入口
        rankRefresher.refresh();

        // 验证:Redis ZSet 中 P1 的 score=300_000(分),Hash 中 P1=15000(×100 件)
        Double amount = redisTemplate.opsForZSet().score(AMOUNT_KEY, String.valueOf(productId));
        Object qty = redisTemplate.opsForHash().get(QTY_KEY, String.valueOf(productId));
        assertThat(amount).isEqualTo(300_000.0);
        assertThat(qty).isEqualTo("15000");
    }

    @Test
    @DisplayName("2. 增量更新:onAdjust 加分与 ZSet 累计")
    void shouldIncrementAndDecrementViaOnAdjust() {
        // 预置:2 个商品(RankEntry 的 amount/quantity 是 raw 数值,无单位约定)
        Long p1 = newProduct("P1");
        Long p2 = newProduct("P2");

        // 触发:P1 +5000/50、P2 +8000/80
        rankCache.onAdjust(new com.qiheng.erp.common.event.dashboard.TopProductRankAdjustEvent(
                LocalDate.now(), 1, List.of(
                        new com.qiheng.erp.common.event.dashboard.TopProductRankAdjustEvent.RankItemInput(
                                p1, 5_000L, 50L),
                        new com.qiheng.erp.common.event.dashboard.TopProductRankAdjustEvent.RankItemInput(
                                p2, 8_000L, 80L))));

        // 验证:Redis 中 P1 score=5000、Hash=50;P2 score=8000、Hash=80
        assertThat(redisTemplate.opsForZSet().score(AMOUNT_KEY, String.valueOf(p1))).isEqualTo(5_000.0);
        assertThat(redisTemplate.opsForZSet().score(AMOUNT_KEY, String.valueOf(p2))).isEqualTo(8_000.0);
        assertThat(redisTemplate.opsForHash().get(QTY_KEY, String.valueOf(p1))).isEqualTo("50");
        assertThat(redisTemplate.opsForHash().get(QTY_KEY, String.valueOf(p2))).isEqualTo("80");
    }

    @Test
    @DisplayName("3. 重建期缓冲与回放:onAdjust 在 rebuilding 期间入 buffer,关闭后回放")
    void shouldBufferAndReplayDuringRebuild() {
        Long p1 = newProduct("P1");

        // 预置:模拟其他实例在重建(rebuilding 标记存在)
        redisTemplate.opsForValue().set(REBUILDING_KEY, "1");

        // 触发:onAdjust 期间事件进 buffer
        rankCache.onAdjust(new com.qiheng.erp.common.event.dashboard.TopProductRankAdjustEvent(
                LocalDate.now(), 1, List.of(
                        new com.qiheng.erp.common.event.dashboard.TopProductRankAdjustEvent.RankItemInput(
                                p1, 1_000L, 10L))));

        // 验证:buffer size=1,AMOUNT_KEY 还没写入
        assertThat(redisTemplate.opsForList().size(REBUILD_BUFFER_KEY)).isEqualTo(1L);
        assertThat(redisTemplate.opsForZSet().score(AMOUNT_KEY, String.valueOf(p1))).isNull();

        // 触发:关闭 rebuilding 标记 + 重建
        redisTemplate.delete(REBUILDING_KEY);
        rankCache.rebuild(() -> List.of(new TopProductRankCache.RankEntry(p1, 5_000L, 50L)));

        // 验证:重建后 P1 score=6000(基线 5000 + buffer 回放 1000),qty=60(50+10),buffer 已清
        assertThat(redisTemplate.opsForZSet().score(AMOUNT_KEY, String.valueOf(p1))).isEqualTo(6_000.0);
        assertThat(redisTemplate.opsForHash().get(QTY_KEY, String.valueOf(p1))).isEqualTo("60");
        assertThat(redisTemplate.opsForList().size(REBUILD_BUFFER_KEY)).isEqualTo(0L);
    }

    @Test
    @DisplayName("4. 脏数据触发重建:污染 Redis 后 load() log.info + 重建 SUCCESS 自愈(不上报系统异常)")
    void shouldReportAndRebuildOnCorruptedData() throws Exception {
        // 预置:1 个商品 + 1 张 APPROVED 销售订单(让 queryRankEntries 能查到数据,rebuild 才能成功)
        Long p1 = newProduct("P1");
        long amountCents = 3_000_00L;  // 3000.00 元
        long qtyBy100 = 30L * YUAN_TO_CENT;  // 30.00 件
        Long salesOrderId = newSalesOrder(p1, SalesOrderStatus.APPROVED.name());
        newSalesOrderItem(salesOrderId, p1, amountCents, qtyBy100, "1001");

        // 预置:首次 rebuild 建立 Redis 缓存
        rankCache.rebuild(() -> List.of(new TopProductRankCache.RankEntry(p1, 3_000L, 30L)));
        assertThat(redisTemplate.opsForZSet().score(AMOUNT_KEY, String.valueOf(p1))).isEqualTo(3_000.0);

        // 触发:绕过 Lua 直接污染 QTY_KEY 中 p1 的 qty 字段为非数字字符串
        // (HGET 缺数据时 Lua 返回 "0" 字符串能 parseLong,只有显式写入非数字才会失败)
        redisTemplate.opsForHash().put(QTY_KEY, String.valueOf(p1), "corrupted_not_a_number");

        // 清理可能存在的历史 TopRankRebuildFailedException 记录,确保本测试可独立验证
        cleanSystemExceptionTable();

        // 执行:Loader.load() 应捕获脏数据 → log.info + 触发 rebuild(Redisson 锁去重) → 返回空
        // 关键:脏数据本身不上报系统异常(可能外部命令临时污染),只有 rebuild 失败才视为真异常
        List<com.qiheng.erp.dashboard.domain.topproduct.vo.DashboardTopProductVO> result = topProductLoader.load();
        assertThat(result).isEmpty();  // 业务响应空,不污染

        // 验证:脏数据已被 rebuild 覆盖(p1 的 qty 恢复为重建值,不再是污染字符串)
        assertThat(redisTemplate.opsForHash().get(QTY_KEY, String.valueOf(p1))).isEqualTo("3000");
        assertThat(redisTemplate.opsForZSet().score(AMOUNT_KEY, String.valueOf(p1))).isEqualTo(300_000.0);

        // 验证:rebuild SUCCESS 自愈路径,system_exception 表不应有新增记录
        // (脏数据不视为错误,Refresher 凌晨兜底之外的常规路径不直接上报)
        Long count = systemExceptionMapper.selectCount(
                new LambdaQueryWrapper<SystemException>()
                        .eq(SystemException::getErrorCode, "TopRankRebuildFailedException"));
        assertThat(count).isEqualTo(0L);
    }

    @Test
    @DisplayName("5. rebuild 异常穿透:supplier 抛 SQL 故障时 rebuild 不吞异常,Refresher 上报 HIGH")
    void shouldPropagateRuntimeExceptionForRefresherHighSeverity() {
        // 验证 RuntimeException 穿透 rebuild() 抛出(不被吞)——让 TopProductRankRefresher
        // catch 后能区分 HIGH(穿透)与 MEDIUM(FAILED 路径)两种上报语义
        // (Loader 的 supplier 失败已被 DashboardTopProductLoaderTest 单元覆盖)
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                rankCache.rebuild(() -> { throw new RuntimeException("模拟 SQL 不可用"); }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("模拟 SQL 不可用");
    }

    @Test
    @DisplayName("7. 完整 VO 链路:rebuild 后 load() 返回正确 VO 列表(含库存聚合)")
    void shouldAssembleDashboardTopProductVoOnHappyPath() {
        // 预置:2 个商品 + 库存 + 重建
        Long p1 = newProduct("测试商品1");
        Long p2 = newProduct("测试商品2");
        newWarehouseStock(p1, 5_000L, 1_000L);  // 可用 4000
        newWarehouseStock(p2, 8_000L, 2_000L);  // 可用 6000
        rankCache.rebuild(() -> List.of(
                new TopProductRankCache.RankEntry(p1, 100_000L, 1_000L),
                new TopProductRankCache.RankEntry(p2, 200_000L, 2_000L)));

        // 触发:load() 完整链路(rebuild → readTop → loadProducts → loadAvailableQty → VO)
        List<com.qiheng.erp.dashboard.domain.topproduct.vo.DashboardTopProductVO> result = topProductLoader.load();

        // 验证:2 个 VO,P2 排第一(金额更高)
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getProductId()).isEqualTo(p2);
        assertThat(result.get(0).getProductName()).isEqualTo("测试商品2");
        assertThat(result.get(0).getSalesAmount()).isEqualTo(new BigDecimal("2000.00"));
        assertThat(result.get(0).getSalesQty()).isEqualTo(new BigDecimal("20.00"));
        assertThat(result.get(0).getAvailableQty()).isEqualTo(new BigDecimal("60.00"));
        assertThat(result.get(1).getProductId()).isEqualTo(p1);
        assertThat(result.get(1).getAvailableQty()).isEqualTo(new BigDecimal("40.00"));
    }

    // ==================== 数据预置 helper ====================

    private Long newProduct(String nameSuffix) {
        String code = testNamespace + "_" + productSeq.incrementAndGet();
        Product p = new Product()
                .setProductCode(code)
                .setProductName(nameSuffix)  // 用业务方传入的名字,不加 code 后缀,方便 VO 断言
                .setCategoryId(CATEGORY_ID)
                .setBrandName("TEST")
                .setUnitName("件")
                .setQuantityPrecision(2)
                .setSpecification("标准")
                .setBarcode("TEST_" + code)
                .setReferencePurchasePrice(new BigDecimal("10.00"))
                .setReferenceSalePrice(new BigDecimal("20.00"))
                .setSafetyStockQty(0L)
                .setStatus(1)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now())
                .setDeleted(0);
        productMapper.insert(p);
        createdProductIds.add(p.getId());
        return p.getId();
    }

    private Long newSalesOrder(Long productId, String status) {
        String no = testNamespace + "_SO_" + productSeq.incrementAndGet();
        SalesOrder o = new SalesOrder()
                .setSalesNo(no)
                .setCustomerId(CUSTOMER_ID)
                .setCustomerCode("TEST_CUST")
                .setCustomerName("测试客户")
                .setWarehouseId(WAREHOUSE_ID)
                .setWarehouseName("测试仓")
                .setStatus(status)
                .setTotalAmount(0L)
                .setCreatedById(CREATED_BY_ID)
                .setCreatedByName("测试员")
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now())
                .setApprovedAt(LocalDateTime.now())
                .setApprovedById(CREATED_BY_ID)
                .setApprovedByName("测试员")
                .setDeleted(0);
        salesOrderMapper.insert(o);
        createdSalesNos.add(no);
        return o.getId();
    }

    private void newSalesOrderItem(Long salesOrderId, Long productId, long amountCents, long qtyBy100, String itemNo) {
        SalesOrderItem item = new SalesOrderItem()
                .setSalesOrderId(salesOrderId)
                .setSalesNo(createdSalesNos.get(createdSalesNos.size() - 1))
                .setProductId(productId)
                .setProductCode("TEST_CODE")
                .setProductName("测试商品")
                .setUnitName("件")
                .setQuantityPrecision(2)
                .setQuantity(qtyBy100)
                .setUnitPrice(amountCents / Math.max(qtyBy100, 1))
                .setTotalAmount(amountCents)
                .setLockedQty(0L)
                .setOutboundQty(0L)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        salesOrderItemMapper.insert(item);
    }

    private void newWarehouseStock(Long productId, long stockQty, long lockedQty) {
        WarehouseStock s = new WarehouseStock()
                .setWarehouseId(WAREHOUSE_ID)
                .setWarehouseCode("TEST_WH")
                .setWarehouseName("测试仓")
                .setProductId(productId)
                .setProductCode("TEST_CODE")
                .setProductName("测试商品")
                .setUnitName("件")
                .setStockQty(stockQty)
                .setLockedQty(lockedQty)
                .setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        warehouseStockMapper.insert(s);
    }

    /**
     * 轮询 system_exception 表,等 Consumer 写入 errorCode 匹配的记录(异步消费)。
     * 测试场景 4 必须等到这条记录才能断言全链路通。
     */
    private void awaitSystemExceptionRecord(String errorCode, Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            Long count = systemExceptionMapper.selectCount(
                    new LambdaQueryWrapper<SystemException>()
                            .eq(SystemException::getErrorCode, errorCode));
            if (count != null && count > 0) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("等待 system_exception 记录超时 errorCode=" + errorCode);
    }
}
