package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.util.RedisUtil;
import com.qiheng.erp.dashboard.cache.model.PendingOrderSnapshot;
import com.qiheng.erp.dashboard.domain.enums.MetricDirection;
import com.qiheng.erp.dashboard.domain.enums.MetricStatus;
import lombok.RequiredArgsConstructor;
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
 * <p>销售额、采购额和库存风险 SKU 的快照可全公司共享；待处理订单按四个来源
 * 保存分项，读取时由调用方按当前权限组合，保证本期与对比期口径一致。</p>
 *
 * <p>key 设计：
 * <ul>
 *   <li>待处理订单分项：{@code dashboard:metric:snapshot:2026-09-01:pending-orders}，TTL 3 天</li>
 *   <li>月快照：{@code dashboard:metric:monthly:2026-08:sales-total}，TTL 90 天</li>
 * </ul>
 *
 * @author Li
 * @since 2026-08-29
 */
@Component
@RequiredArgsConstructor
public class DashboardPrevValueCache {

    /** 日快照 key 前缀 */
    private static final String DAILY_KEY_PREFIX = "dashboard:metric:snapshot:";
    /** 待处理订单分项快照 key 后缀 */
    private static final String PENDING_ORDER_SNAPSHOT_KEY_SUFFIX = ":pending-orders";
    /** 月快照 key 前缀 */
    private static final String MONTHLY_KEY_PREFIX = "dashboard:metric:monthly:";
    /** 日快照 TTL：3 天（覆盖周末 + 节假日调度异常场景） */
    private static final Duration DAILY_TTL = Duration.ofDays(3);
    /** 月快照 TTL：90 天（跨月 30 天 + 两个自然月兜底） */
    private static final Duration MONTHLY_TTL = Duration.ofDays(90);
    /** 日快照日期格式 */
    private static final DateTimeFormatter DAILY_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE;
    /** 月快照日期格式 */
    private static final DateTimeFormatter MONTHLY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM");

    private final RedisUtil redisUtil;

    /**
     * 运营类日快照类型
     */
    public enum DailySnapshotType {

        /** 库存风险 SKU 数（昨日 23:55 快照） */
        STOCK_RISK_COUNT("stock-risk-count");

        private final String keySegment;

        DailySnapshotType(String keySegment) {
            this.keySegment = keySegment;
        }

        public String keySegment() {
            return keySegment;
        }
    }

    /**
     * 财务类月快照类型
     */
    public enum MonthlySnapshotType {
        /** 上月整月销售累计 */
        SALES_TOTAL("sales-total"),

        /** 上月整月采购累计 */
        PURCHASE_TOTAL("purchase-total");

        private final String keySegment;

        MonthlySnapshotType(String keySegment) {
            this.keySegment = keySegment;
        }

        public String keySegment() {
            return keySegment;
        }
    }

    /**
     * 读取日快照
     *
     * @param date 快照对应日期（通常是昨天）
     * @param type 快照类型
     * @return 快照值；缓存 miss 或反序列化失败时返回 null，由调用方 fallback
     */
    public BigDecimal getDailySnapshot(LocalDate date, DailySnapshotType type) {
        String key = DAILY_KEY_PREFIX + date.format(DAILY_FORMATTER) + ":" + type.keySegment();
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
        String key = DAILY_KEY_PREFIX + date.format(DAILY_FORMATTER) + ":" + type.keySegment();
        redisUtil.set(key, value.toPlainString(), DAILY_TTL);
    }

    /**
     * 读取待处理订单分项快照。
     *
     * @param date 快照对应日期（通常是昨天）
     * @return 分项快照；缓存 miss 或反序列化失败时返回 null
     */
    public PendingOrderSnapshot getPendingOrderSnapshot(LocalDate date) {
        return redisUtil.getObject(pendingOrderSnapshotKey(date), PendingOrderSnapshot.class);
    }

    /**
     * 写入待处理订单分项快照。
     *
     * @param date 快照对应日期
     * @param snapshot 按采购、销售、入库、出库来源拆分的待办数量
     */
    public void putPendingOrderSnapshot(LocalDate date, PendingOrderSnapshot snapshot) {
        redisUtil.setObject(pendingOrderSnapshotKey(date), snapshot, DAILY_TTL);
    }

    private static String pendingOrderSnapshotKey(LocalDate date) {
        return DAILY_KEY_PREFIX + date.format(DAILY_FORMATTER) + PENDING_ORDER_SNAPSHOT_KEY_SUFFIX;
    }

    /**
     * 读取月快照
     *
     * @param month 快照对应月份（通常是上个月）
     * @param type 快照类型
     * @return 快照值；缓存 miss 时返回 null，由调用方 fallback
     */
    public BigDecimal getMonthlySnapshot(YearMonth month, MonthlySnapshotType type) {
        String key = MONTHLY_KEY_PREFIX + month.format(MONTHLY_FORMATTER) + ":" + type.keySegment();
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
        String key = MONTHLY_KEY_PREFIX + month.format(MONTHLY_FORMATTER) + ":" + type.keySegment();
        redisUtil.set(key, value.toPlainString(), MONTHLY_TTL);
    }

    /**
     * 计算变化率百分比，保留 2 位小数；无可比基线时返回 null
     *
     * <p>公式：{@code (current - prev) / abs(prev) × 100}，变化率方向始终反映本期相对上期的增减；
     * 上期为负值时仍保留真实业务值，只使用绝对值作为百分比基线。</p>
     *
     * @param current 当前值
     * @param prev    对比期值
     * @return 变化率（百分数，例如 12.80 表示 +12.80%；无可比基线时返回 {@code null}）
     */
    public static BigDecimal computeChangeRate(BigDecimal current, BigDecimal prev) {
        if (current == null) {
            current = BigDecimal.ZERO;
        }
        if (prev == null) {
            return null;
        }
        if (prev.signum() == 0) {
            // 0 到非 0 没有有意义的百分比基线；0 到 0 则可明确视为无变化。
            return current.signum() == 0 ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP) : null;
        }
        return current.subtract(prev)
                .multiply(BigDecimal.valueOf(100))
                // 上期为负值时仍以绝对值作为基线，亏损收窄才能正确表达为改善。
                .divide(prev.abs(), 2, RoundingMode.HALF_UP);
    }

    /**
     * 根据变化率与指标方向判定风险色语义
     *
     * <table>
     *   <tr><th>变化率</th><th>POSITIVE（越大越好）</th><th>NEGATIVE（越小越好）</th></tr>
     *   <tr><td>≥ +5%</td><td>GOOD</td><td>RISK</td></tr>
     *   <tr><td>0 ~ +5%</td><td>WATCH</td><td>WATCH</td></tr>
     *   <tr><td>0</td><td>NEUTRAL</td><td>NEUTRAL</td></tr>
     *   <tr><td>-5% ~ 0</td><td>RISK</td><td>WATCH</td></tr>
     *   <tr><td>≤ -5%</td><td>RISK</td><td>GOOD</td></tr>
     * </table>
     *
     * @param changeRate 变化率（百分数）
     * @param direction  指标方向
     * @return 风险色语义枚举
     */
    public static MetricStatus computeStatus(BigDecimal changeRate, MetricDirection direction) {
        // 处理 null 值
        if (changeRate == null || changeRate.signum() == 0) {
            return MetricStatus.NEUTRAL;
        }
        // 如果指标方向为 NEGATIVE，将变化率取负 , 以符合反向指标的定义
        if (direction == MetricDirection.NEGATIVE) {
            changeRate = changeRate.negate();
        }
        // 如果变化率为 正向指标且大于等于 5%，返回良好风险色语义
        if (changeRate.compareTo(BigDecimal.valueOf(5)) >= 0) {
            return MetricStatus.GOOD;
        }
        if (changeRate.compareTo(BigDecimal.valueOf(-5)) <= 0) {
            return MetricStatus.RISK;
        }
        return MetricStatus.WATCH;
    }
}
