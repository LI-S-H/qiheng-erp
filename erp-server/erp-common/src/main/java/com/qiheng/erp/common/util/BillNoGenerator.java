package com.qiheng.erp.common.util;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.function.LongSupplier;
import java.util.Collection;

/**
 * 业务单号生成器。
 * <p>
 * 格式：{前缀}{yyyyMMdd}{5位序号}，按天重置。
 * Redis key：bill:no:{前缀}:{yyyyMMdd}
 *
 * @author Li
 * @since 2026-07-25
 */
@Component
public class BillNoGenerator {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyyMM");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String KEY_PREFIX = "bill:no:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成业务单号
     *
     * @param prefix 单据前缀，如 RK（入库）、CK（出库）、PO（采购）、SO（销售）
     * @return 单号，如 RK2026072500001
     */
    public String nextNo(String prefix) {
        return nextNo(prefix, () -> 0L);
    }

    /**
     * 生成业务单号。Redis 序列丢失时由调用方按对应业务表提供当天最大序号，
     * 避免公共组件依赖具体业务表，也避免 Redis 重启后从 00001 重新编号。
     */
    public String nextNo(String prefix, LongSupplier maxExistingSequenceSupplier) {
        String month = LocalDate.now().format(MONTH_FMT);
        String day = LocalDate.now().format(DAY_FMT);
        String key = KEY_PREFIX + prefix + ":" + month;
        if (!Boolean.TRUE.equals(stringRedisTemplate.hasKey(key))) {
            stringRedisTemplate.opsForValue().setIfAbsent(
                    key, String.valueOf(Math.max(0L, maxExistingSequenceSupplier.getAsLong())));
        }
        Long seq = stringRedisTemplate.opsForValue().increment(key);
        if (seq == null) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "生成单号失败");
        }
        return prefix + day + String.format("%05d", seq);
    }

    /**
     * 从同一业务表当天已有单号中提取最大序号，供 Redis 首次初始化时使用。
     */
    public long findMaxExistingSequence(String prefix, Collection<Object> billNos) {
        String dayPrefix = prefix + LocalDate.now().format(DAY_FMT);
        return billNos.stream()
                .filter(java.util.Objects::nonNull)
                .map(String::valueOf)
                .filter(billNo -> billNo.startsWith(dayPrefix) && billNo.length() >= dayPrefix.length() + 5)
                .map(billNo -> billNo.substring(billNo.length() - 5))
                .filter(sequence -> sequence.chars().allMatch(Character::isDigit))
                .mapToLong(Long::parseLong)
                .max()
                .orElse(0L);
    }
}
