package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.util.RedisUtil;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;

/**
 * 工作台指标"对比期"值缓存。
 *
 * <p>4 个首屏指标分两类：
 * <ul>
 *   <li><b>财务类（月累计）</b>：本月销售额 / 本月毛利额，对比"上月整月累计"。
 *       由 {@code DashboardMonthlySnapshotJob} 在每月最后一天 23:55 拍快照写入。</li>
 *   <li><b>运营类（状态快照）</b>：待处理订单 / 库存风险 SKU，对比"昨日 23:55 快照"。
 *       由 {@code DashboardDailySnapshotJob} 在每日 23:55 拍快照写入。</li>
 * </ul>
 *
 * <p>快照全公司共享一份（这些指标的 prev 值与用户权限无关），
 * 缓存 miss 时由调用方 fallback 实时聚合，本次接口读取完成后由定时任务补齐。</p>
 *
 * <p>key 设计：
 * <ul>
 *   <li>日快照：{@code dashboard:metric:snapshot:2026-09-01:pendingCount}，TTL 3 天</li>
 *   <li>月快照：{@code dashboard:metric:monthly:2026-08:salesTotal}，TTL 90 天</li>
 * </ul>
 *
 * @author Li
 * @since 2026-08-29
 */
@Component
public class DashboardPrevValueCache {

    /** 日快照 key 前缀 */
    private static final String DAILY_KEY_PREFIX = "dashboard:metric:snapshot:";

    /** 月快照 key 前缀 */
    private static final String MONTHLY_KEY_PREFIX = "dashboard:metric:monthly:";

    /** 日快照 TTL：3 天（覆盖周末 + 节假日调度异常场景） */
    private static final Duration DAILY_TTL = Duration.ofDays(3);

    /** 月快照 TTL：90 天（跨月 30 天 + 两个自然月兜底） */
    private static final Duration MONTHLY_TTL = Duration.ofDays(90);

    private static final DateTimeFormatter DAILY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final DateTimeFormatter MONTHLY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final RedisUtil redisUtil;

    public DashboardPrevValueCache(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    /**
     * 运营类日快照类型
     */
    public enum DailySnapshotType {
        /** 待处理订单数（昨日 23:55 快照） */
        PENDING_COUNT,

        /** 库存风险 SKU 数（昨日 23:55 快照） */
        STOCK_RISK_COUNT
    }

    /**
     * 财务类月快照类型
     */
    public enum MonthlySnapshotType {
        /** 上月整月销售累计 */
        SALES_TOTAL,

        /** 上月整月采购累计 */
        PURCHASE_TOTAL
    }

    /**
     * 读取日快照
     *
     * @param date 快照对应日期（通常是昨天）
     * @param type 快照类型
     * @return 快照值；缓存 miss 或反序列化失败时返回 null，由调用方 fallback
     */
    public BigDecimal getDailySnapshot(LocalDate date, DailySnapshotType type) {
        String key = DAILY_KEY_PREFIX + date.format(DAILY_FORMATTER) + ":" + type.name();
        String raw = redisUtil.get(key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 写入日快照
     *
     * @param date 快照对应日期
     * @param type 快照类型
     * @param value 快照值
     */
    public void putDailySnapshot(LocalDate date, DailySnapshotType type, BigDecimal value) {
        String key = DAILY_KEY_PREFIX + date.format(DAILY_FORMATTER) + ":" + type.name();
        redisUtil.set(key, value.toPlainString(), DAILY_TTL);
    }

    /**
     * 读取月快照
     *
     * @param month 快照对应月份（通常是上个月）
     * @param type 快照类型
     * @return 快照值；缓存 miss 时返回 null，由调用方 fallback
     */
    public BigDecimal getMonthlySnapshot(YearMonth month, MonthlySnapshotType type) {
        String key = MONTHLY_KEY_PREFIX + month.format(MONTHLY_FORMATTER) + ":" + type.name();
        String raw = redisUtil.get(key);
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 写入月快照
     *
     * @param month 快照对应月份
     * @param type 快照类型
     * @param value 快照值
     */
    public void putMonthlySnapshot(YearMonth month, MonthlySnapshotType type, BigDecimal value) {
        String key = MONTHLY_KEY_PREFIX + month.format(MONTHLY_FORMATTER) + ":" + type.name();
        redisUtil.set(key, value.toPlainString(), MONTHLY_TTL);
    }

    /**
     * 计算变化率百分比，保留 2 位小数；prev 为 0 或 null 时返回 0（避免除零）
     *
     * @param current 当前值
     * @param prev 对比期值
     * @return 变化率（百分数，例如 12.80 表示 +12.80%）
     */
    public static BigDecimal computeChangeRate(BigDecimal current, BigDecimal prev) {
        if (prev == null || prev.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (current == null) {
            current = BigDecimal.ZERO;
        }
        return current.subtract(prev)
                .multiply(BigDecimal.valueOf(100))
                .divide(prev.abs(), 2, RoundingMode.HALF_UP);
    }

    /**
     * 根据变化率自动判定指标风险色语义
     *
     * @param changeRate 变化率（百分数）
     * @return good / watch / risk / neutral
     */
    public static String computeStatus(BigDecimal changeRate) {
        if (changeRate == null || changeRate.signum() == 0) {
            return "neutral";
        }
        // 上升 5% 以上视为良好；小幅波动为关注；下降视为风险
        if (changeRate.compareTo(BigDecimal.valueOf(5)) >= 0) {
            return "good";
        }
        if (changeRate.signum() > 0) {
            return "watch";
        }
        return "risk";
    }
}
