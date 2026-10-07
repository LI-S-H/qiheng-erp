package com.qiheng.erp.purchase.service.scoring;

import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** 使用独立负数测试 ID，仅清理本测试创建的单键，不接触真实供应商缓存。 */
@EnabledIfSystemProperty(named = "score.redis.it", matches = "true")
class SupplierQualityAmountRedisIT {
    @Test
    void realRedisKeepsTtlPrecisionAndOrderIdempotency() {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + System.getProperty("score.it.redis.host", "localhost")
                + ":" + Integer.getInteger("score.it.redis.port", 6379))
                .setDatabase(Integer.getInteger("score.it.redis.database", 0));
        var client = Redisson.create(config);
        RedissonConnectionFactory factory = new RedissonConnectionFactory(client);
        StringRedisTemplate redis = new StringRedisTemplate(factory);
        long supplierId = -Math.abs(System.nanoTime());
        assertTrue(supplierId < 0);
        String key = SupplierScoreRedisKeys.qualityAmountKey(supplierId);
        SupplierQualityAmountCacheService cache = new SupplierQualityAmountCacheService(redis);
        LocalDate date = LocalDate.of(2026, 9, 26);
        var initial = new SupplierQualityAmountCacheService.Amounts(new BigInteger("999999999999999999999999"), BigInteger.ONE);
        var delta = new SupplierQualityAmountCacheService.Amounts(BigInteger.TEN, BigInteger.ONE);
        try {
            assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
            cache.overwrite(supplierId, date, initial);
            Long ttl = redis.getExpire(key, TimeUnit.SECONDS);
            assertNotNull(ttl); assertTrue(ttl > 86300 && ttl <= 86400);
            assertEquals(SupplierQualityAmountCacheService.IncrementResult.APPLIED,
                    cache.incrementCompletedOrder(supplierId, 2L, date, delta));
            assertEquals(SupplierQualityAmountCacheService.IncrementResult.ALREADY_APPLIED,
                    cache.incrementCompletedOrder(supplierId, 2L, date, delta));
            assertEquals(initial.qualifiedRaw().add(BigInteger.TEN), cache.read(supplierId, date).qualifiedRaw());
            assertEquals(5L, redis.opsForHash().size(key));
            cache.incrementCompletedOrder(supplierId, 3L, date,
                    new SupplierQualityAmountCacheService.Amounts(BigInteger.ZERO, BigInteger.ZERO));
            assertEquals(SupplierQualityAmountCacheService.IncrementResult.ALREADY_APPLIED,
                    cache.incrementCompletedOrder(supplierId, 3L, date, delta));
            cache.overwrite(supplierId, date, initial, java.util.Set.of(2L, 3L));
            for (Long orderId : java.util.List.of(2L, 3L)) {
                assertEquals(SupplierQualityAmountCacheService.IncrementResult.ALREADY_APPLIED,
                        cache.incrementCompletedOrder(supplierId, orderId, date, delta));
            }
            assertEquals(initial, cache.read(supplierId, date));
            assertNull(cache.read(supplierId, date.plusDays(1)));
            redis.expire(key, Duration.ZERO);
            assertEquals(SupplierQualityAmountCacheService.IncrementResult.CACHE_MISS,
                    cache.incrementCompletedOrder(supplierId, 4L, date, delta));
            assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
            System.out.println("SCORE_REDIS_OK ttlSeconds=" + ttl + " exactBigInteger=true idempotent=true expiredMiss=true");
        } finally {
            redis.delete(key);
            client.shutdown();
        }
    }
}