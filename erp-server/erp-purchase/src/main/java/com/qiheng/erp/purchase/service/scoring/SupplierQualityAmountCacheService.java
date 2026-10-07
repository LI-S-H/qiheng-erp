package com.qiheng.erp.purchase.service.scoring;

import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;

/**
 * 供应商质量金额事实缓存。金额使用「数量存储值 × 单价分」的原始整数，避免逐批舍入。
 * 仅缓存供应商总额，供货产品质量分由数据库事实计算并落库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierQualityAmountCacheService {

    private static final String RULE_VERSION = "quality-amount-v2";
    private static final String FIELD_DATE = "businessDate";
    private static final String FIELD_VERSION = "ruleVersion";
    private static final String FIELD_QUALIFIED = "qualifiedTotal";
    private static final String FIELD_DEFECTIVE = "defectiveTotal";
    private static final String APPLIED_PREFIX = "applied:";
    private static final Duration TTL = Duration.ofHours(24);
    // Lua 只校验和写字符串，金额加法在 Java BigInteger 中完成，避免 Lua 浮点精度丢失。
    private static final DefaultRedisScript<Long> WRITE_INCREMENT = new DefaultRedisScript<>("""
            if redis.call('PTTL', KEYS[1]) <= 0
              or redis.call('HGET', KEYS[1], 'businessDate') ~= ARGV[1]
              or redis.call('HGET', KEYS[1], 'ruleVersion') ~= ARGV[2] then return 0 end
            if redis.call('HEXISTS', KEYS[1], ARGV[7]) == 1 then return 2 end
            if redis.call('HGET', KEYS[1], 'qualifiedTotal') ~= ARGV[3]
              or redis.call('HGET', KEYS[1], 'defectiveTotal') ~= ARGV[4] then return 0 end
            redis.call('HSET', KEYS[1], 'qualifiedTotal', ARGV[5], 'defectiveTotal', ARGV[6], ARGV[7], '1')
            return 1
            """, Long.class);

    private final StringRedisTemplate redisTemplate;

    /**
     * 供应商质量金额缓存记录。
     */
    public record Amounts(BigInteger qualifiedRaw, BigInteger defectiveRaw) {
        public Amounts {
            Objects.requireNonNull(qualifiedRaw);
            Objects.requireNonNull(defectiveRaw);
            if (qualifiedRaw.signum() < 0 || defectiveRaw.signum() < 0) {
                throw new IllegalArgumentException("质量金额不能为负");
            }
        }
    }

    public enum IncrementResult {
        /**
         * 成功应用。
         */
        APPLIED,
        /**
         * 已应用。
         */
        ALREADY_APPLIED,
        /**
         * 缓存缺失。
         */
        CACHE_MISS
    }

    /**
     * 读取当日且规则版本匹配的完整质量金额缓存。
     *
     * @param supplierId 供应商 ID
     * @param businessDate 上海时区的评分业务日期
     * @return 合格与不合格原始金额；缓存缺失、失效或读取失败时返回 null
     */
    public Amounts read(Long supplierId, LocalDate businessDate) {
        try {
            Map<Object, Object> values = redisTemplate.opsForHash()
                    .entries(SupplierScoreRedisKeys.qualityAmountKey(supplierId));
            if (!businessDate.toString().equals(values.get(FIELD_DATE))
                    || !RULE_VERSION.equals(values.get(FIELD_VERSION))) {
                return null;
            }
            Object qualified = values.get(FIELD_QUALIFIED);
            Object defective = values.get(FIELD_DEFECTIVE);
            if (qualified == null || defective == null) {
                return null;
            }
            return new Amounts(new BigInteger(qualified.toString()), new BigInteger(defective.toString()));
        } catch (RuntimeException e) {
            log.warn("质量金额缓存读取失败，降级数据库 supplierId={}", supplierId, e);
            return null;
        }
    }

    /**
     * 原子覆盖不含今日订单标记的质量金额快照；完整重建应使用带订单集合的重载。
     *
     * @param supplierId 供应商 ID
     * @param businessDate 上海时区的评分业务日期
     * @param amounts 供应商合格与不合格原始金额总额
     */
    public void overwrite(Long supplierId, LocalDate businessDate, Amounts amounts) {
        overwrite(supplierId, businessDate, amounts, Set.of());
    }

    /**
     * 将质量总额与今日已包含订单原子覆盖，并设置 24 小时 TTL。
     * 调用方须持有供应商锁，避免全量基线覆盖并发增量。
     *
     * @param supplierId 供应商 ID
     * @param businessDate 上海时区的评分业务日期
     * @param amounts 供应商合格与不合格原始金额总额
     * @param includedTodayOrderIds 全量基线已包含的今日完全入库订单 ID 集合
     */
    public void overwrite(Long supplierId, LocalDate businessDate, Amounts amounts, Set<Long> includedTodayOrderIds) {
        String key = SupplierScoreRedisKeys.qualityAmountKey(supplierId);
        String temporaryKey = key + ":tmp:" + UUID.randomUUID();
        try {
            // 1. 构建新总额
            Map<String, String> values = new HashMap<>(Map.of(
                    FIELD_DATE, businessDate.toString(),
                    FIELD_VERSION, RULE_VERSION,
                    FIELD_QUALIFIED, amounts.qualifiedRaw().toString(),
                    FIELD_DEFECTIVE, amounts.defectiveRaw().toString()));
            // 2. 标记今日已包含订单
            includedTodayOrderIds.forEach(id -> values.put(APPLIED_PREFIX + id, "1"));
            redisTemplate.opsForHash().putAll(temporaryKey, values);
            if (!Boolean.TRUE.equals(redisTemplate.expire(temporaryKey, TTL))) {
                throw new IllegalStateException("质量金额临时缓存设置 TTL 失败");
            }
            // 3. 重命名临时缓存为正式缓存
            redisTemplate.rename(temporaryKey, key);
        } catch (RuntimeException e) {
            // 数据库校正结果仍会落库，失效旧基线避免后续暖缓存将质量分回退。
            try {
                redisTemplate.delete(key);
            } catch (RuntimeException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            try {
                redisTemplate.delete(temporaryKey);
            } catch (RuntimeException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            log.warn("质量金额缓存覆盖失败，后续降级数据库 supplierId={}", supplierId, e);
            throw e;
        }
    }

    /**
     * 白天增量的底层原子写。调用方须按供应商持有重算锁，并确保订单首次完全入库且事务已提交。
     * 缓存缺失时不能从零累加，应先从数据库全量重建；重建已包含该订单，不再追加。
     *
     * @param supplierId 供应商 ID
     * @param orderId 当日首次完全入库的采购单 ID
     * @param businessDate 上海时区的评分业务日期
     * @param contribution 该订单的合格与不合格原始金额
     * @return 已追加、已计入或缓存失效的处理结果
     */
    public IncrementResult incrementCompletedOrder(Long supplierId, Long orderId,
                                                    LocalDate businessDate, Amounts contribution) {
        String key = SupplierScoreRedisKeys.qualityAmountKey(supplierId);
        try {
            // 1. 读取缓存
            Map<Object, Object> values = redisTemplate.opsForHash().entries(key);
            // 2. 检查缓存是否过期, 或缺失必要字段,或者版本不匹配
            if (!businessDate.toString().equals(values.get(FIELD_DATE))
                    || !RULE_VERSION.equals(values.get(FIELD_VERSION))
                    || values.get(FIELD_QUALIFIED) == null || values.get(FIELD_DEFECTIVE) == null) {
                return IncrementResult.CACHE_MISS;
            }
            // 3. 检查订单是否已应用
            String appliedField = APPLIED_PREFIX + orderId;
            if (values.containsKey(appliedField)) {
                return IncrementResult.ALREADY_APPLIED;
            }
            // 4. 计算新总额
            Amounts next = new Amounts(
                    new BigInteger(values.get(FIELD_QUALIFIED).toString()).add(contribution.qualifiedRaw()),
                    new BigInteger(values.get(FIELD_DEFECTIVE).toString()).add(contribution.defectiveRaw()));
            // 原子检查 TTL 与旧总额，避免读取后恰好过期时 HSET 创建永久残缺键。
            Long result = redisTemplate.execute(WRITE_INCREMENT, List.of(key), businessDate.toString(), RULE_VERSION,
                    values.get(FIELD_QUALIFIED).toString(), values.get(FIELD_DEFECTIVE).toString(),
                    next.qualifiedRaw().toString(), next.defectiveRaw().toString(), appliedField);
            return Long.valueOf(1).equals(result) ? IncrementResult.APPLIED
                    : Long.valueOf(2).equals(result) ? IncrementResult.ALREADY_APPLIED : IncrementResult.CACHE_MISS;
        } catch (RuntimeException e) {
            log.warn("质量金额增量缓存失败 supplierId={} orderId={}", supplierId, orderId, e);
            throw e;
        }
    }
}
