package com.qiheng.erp.common.util;

import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * 业务单号生成器。
 * <p>
 * 格式：{前缀}{yyyyMMdd}{5位序号}，按天重置。
 * Redis key：bill:no:{前缀}:{yyyyMMdd}。同一前缀、同一天的初始化和自增在同一把分布式锁内完成。
 *
 * @author Li
 * @since 2026-07-25
 */
@Component
public class BillNoGenerator extends AbstractSequenceGenerator {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String KEY_PREFIX = "bill:no:";
    private static final String LOCK_KEY_PREFIX = "bill:no:lock:";
    public static final int SEQUENCE_WIDTH = 5;
    private static final long MAX_SEQUENCE = 99_999L;

    public BillNoGenerator(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        super(stringRedisTemplate, redissonClient, "单据号");
    }

    /**
     * 生成业务单号。
     *
     * @param prefix 单据前缀，如 RK（入库）、CK（出库）、PO（采购）、SO（销售）
     * @return 单号，如 RK2026072500001
     */
    public String nextNo(String prefix) {
        return nextNo(prefix, dayPrefix -> 0L);
    }

    /**
     * 生成业务单号。Redis 序列丢失时由调用方按对应业务表提供当天最大序号。
     * 自定义 Mapper 查询必须包含逻辑删除记录，因为已逻辑删除的单号仍受唯一索引约束。
     *
     * @param prefix 单据前缀
     * @param maxExistingSequenceFinder 接收完整当天前缀（如 {@code PO20260814}）并返回最大五位序号
     */
    public String nextNo(String prefix, ToLongFunction<String> maxExistingSequenceFinder) {
        if (prefix == null || prefix.isBlank()) {
            throw failure("单据前缀不能为空");
        }
        String day = LocalDate.now().format(DAY_FMT);
        String dayPrefix = prefix + day;
        String redisKey = KEY_PREFIX + prefix + ":" + day;
        SequenceDefinition definition = new SequenceDefinition(
                LOCK_KEY_PREFIX + prefix + ":" + day,
                redisKey,
                dayPrefix,
                SEQUENCE_WIDTH,
                MAX_SEQUENCE
        );
        return nextSequence(definition, () -> maxExistingSequenceFinder.applyAsLong(dayPrefix), "当天单据号已用尽");
    }

    /**
     * 从同一业务表当天已有单号中提取最大序号，供无法直接聚合的调用方使用。
     */
    public long findMaxExistingSequence(String prefix, Collection<Object> billNos) {
        String dayPrefix = prefix + LocalDate.now().format(DAY_FMT);
        return billNos.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(billNo -> billNo.startsWith(dayPrefix) && billNo.length() == dayPrefix.length() + SEQUENCE_WIDTH)
                .map(billNo -> billNo.substring(dayPrefix.length()))
                .filter(sequence -> sequence.chars().allMatch(Character::isDigit))
                .mapToLong(Long::parseLong)
                .max()
                .orElse(0L);
    }
}