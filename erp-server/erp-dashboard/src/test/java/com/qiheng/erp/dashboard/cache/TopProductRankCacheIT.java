package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.event.dashboard.TopProductRankAdjustEvent;
import com.qiheng.erp.common.event.dashboard.TopProductRankAdjustEvent.RankItemInput;
import com.qiheng.erp.common.util.RedisUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TOP 商品排行 Redis 集成测试：用真实本地 Redis 跑 Lua 脚本。
 * 验证 {@link TopProductRankCache} 的几个关键 Lua 脚本在 Redis 7+ 上的实际行为，
 * 避免单元测试 mock 掉 Lua 真实解析逻辑。
 *
 * <p>启用条件：-Dredis.it=true；CI 默认不跑（避免无 Redis 环境失败）。</p>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.data.redis.host=localhost",
        "spring.data.redis.port=6379"
})
@EnabledIfSystemProperty(named = "redis.it", matches = "true")
class TopProductRankCacheIT {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RedisUtil redisUtil;

    private TopProductRankCache cache;

    private static final String AMOUNT_KEY = "dashboard:top-product:sales-amount";
    private static final String QTY_KEY = "dashboard:top-product:sales-qty";
    private static final String REBUILDING_KEY = "dashboard:top-product:rebuilding";
    private static final String REBUILD_BUFFER_KEY = "dashboard:top-product:rebuild-buffer";

    @BeforeEach
    void setUp() {
        cache = new TopProductRankCache(redisTemplate, redissonClient);
        // 清理测试残留
        redisUtil.delete(AMOUNT_KEY);
        redisUtil.delete(QTY_KEY);
        redisUtil.delete(REBUILDING_KEY);
        redisUtil.delete(REBUILD_BUFFER_KEY);
    }

    @AfterEach
    void tearDown() {
        redisUtil.delete(AMOUNT_KEY);
        redisUtil.delete(QTY_KEY);
        redisUtil.delete(REBUILDING_KEY);
        redisUtil.delete(REBUILD_BUFFER_KEY);
    }

    @Test
    void shouldIncrementAmountAndQtyViaRealLuaScript() {
        cache.onAdjust(new TopProductRankAdjustEvent(LocalDate.now(), 1,
                List.of(new RankItemInput(1001L, 5000L, 50L))));

        Double amount = redisTemplate.opsForZSet().score(AMOUNT_KEY, "1001");
        Object qty = redisTemplate.opsForHash().get(QTY_KEY, "1001");
        assertThat(amount).isEqualTo(5000.0);
        assertThat(qty).isEqualTo("50");
    }

    @Test
    void shouldBufferEventsWhenRebuildingAndReplayAfter() {
        // 1. 进入重建窗口
        redisTemplate.opsForValue().set(REBUILDING_KEY, "1");

        // 2. 发送两个事件，全部进缓冲
        cache.onAdjust(new TopProductRankAdjustEvent(LocalDate.now(), 1,
                List.of(new RankItemInput(2001L, 1000L, 10L))));
        cache.onAdjust(new TopProductRankAdjustEvent(LocalDate.now(), 1,
                List.of(new RankItemInput(2001L, 2000L, 20L))));

        // 3. 验证缓冲队列有 2 条事件
        Long buffered = redisTemplate.opsForList().size(REBUILD_BUFFER_KEY);
        assertThat(buffered).isEqualTo(2L);

        // 4. 关闭重建标记，验证后续增量正常写
        redisTemplate.delete(REBUILDING_KEY);
        cache.onAdjust(new TopProductRankAdjustEvent(LocalDate.now(), 1,
                List.of(new RankItemInput(2001L, 500L, 5L))));

        // 5. 此时 2001 的 amount 应该是 缓冲 1000+2000 + 后续 500 = 3500
        Double amount = redisTemplate.opsForZSet().score(AMOUNT_KEY, "2001");
        assertThat(amount).isEqualTo(3500.0);
    }

    @Test
    void shouldRemoveProductWhenNetAmountDropsToZeroOrNegative() {
        // 初始加 1000 分
        cache.onAdjust(new TopProductRankAdjustEvent(LocalDate.now(), 1,
                List.of(new RankItemInput(3001L, 1000L, 10L))));
        assertThat(redisTemplate.opsForZSet().score(AMOUNT_KEY, "3001")).isEqualTo(1000.0);

        // 扣 1500 分（超过 1000，净额变 -500），Lua 应自动 ZREM + HDEL
        cache.onAdjust(new TopProductRankAdjustEvent(LocalDate.now(), -1,
                List.of(new RankItemInput(3001L, 1500L, 15L))));

        assertThat(redisTemplate.opsForZSet().score(AMOUNT_KEY, "3001")).isNull();
        assertThat(redisTemplate.opsForHash().get(QTY_KEY, "3001")).isNull();
    }

    @Test
    void shouldReadTopWithAmountAndQuantityInOneShot() {
        // 注入两条：商品 A 3000/30，商品 B 1000/10
        cache.onAdjust(new TopProductRankAdjustEvent(LocalDate.now(), 1,
                List.of(new RankItemInput(4001L, 3000L, 30L), new RankItemInput(4002L, 1000L, 10L))));

        List<TopProductRankCache.RankEntry> top = cache.readTop(10);

        assertThat(top).hasSize(2);
        assertThat(top.get(0).productId()).isEqualTo(4001L);
        assertThat(top.get(0).amount()).isEqualTo(3000L);
        assertThat(top.get(0).quantity()).isEqualTo(30L);
        assertThat(top.get(1).productId()).isEqualTo(4002L);
        assertThat(top.get(1).amount()).isEqualTo(1000L);
        assertThat(top.get(1).quantity()).isEqualTo(10L);
    }
}