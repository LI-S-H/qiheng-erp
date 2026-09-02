package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.event.dashboard.DashboardTrendMetric;
import com.qiheng.erp.common.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作台经营趋势的每日金额缓存。
 *
 * <p>每个指标维度使用一个 Redis Hash，field 为 ISO 日期，value 为原始分值。
 * 单日数据可独立删除和回填，不会因一张订单变更而让 30 日趋势整体失效。</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardTrendDailyAmountCache {

    private static final String CACHE_KEY_PREFIX = "dashboard:trend:daily-amount:";
    private static final Duration CACHE_TTL = Duration.ofDays(35);
    private static final int RETAIN_DAYS = 35;

    private final RedisUtil redisUtil;

    public record GetResult(Map<LocalDate, Long> hits, List<LocalDate> misses) {}

    /** 批量读取指定日期的原始分值；不存在或格式异常的字段按 miss 处理，同时返回命中和缺失列表。 */
    public GetResult get(DashboardTrendMetric metric, List<LocalDate> dates) {
        if (dates.isEmpty()) {
            return new GetResult(Map.of(), List.of());
        }
        // 1. 从缓存中批量获取所有日期的对应数据
        List<String> values = redisUtil.hashMultiGet(key(metric), dates.stream().map(LocalDate::toString).toList());
        Map<LocalDate, Long> hits = new HashMap<>();
        List<LocalDate> misses = new ArrayList<>();
        // 2. 解析缓存值，将 null 值和格式异常值记录为 misses
        for (int index = 0; index < dates.size(); index++) {
            String value = values.get(index);
            if (value == null) {
                misses.add(dates.get(index));
                continue;
            }
            try {
                hits.put(dates.get(index), Long.parseLong(value));
            } catch (NumberFormatException exception) {
                log.warn("工作台趋势缓存字段格式异常，按 miss 处理 metric={} date={} value={}",
                        metric, dates.get(index), value);
                misses.add(dates.get(index));
            }
        }
        return new GetResult(hits, misses);
    }

    /** 批量回填同一维度的每日原始分值，并清理窗口外字段。 */
    public void put(DashboardTrendMetric metric, Map<LocalDate, Long> amountsByDay) {
        if (amountsByDay.isEmpty()) {
            return;
        }
        Map<String, String> fields = new HashMap<>();
        // 1. 遍历所有日期，将日期转换为 ISO 格式并添加到 Map 中
        amountsByDay.forEach((date, amount) -> fields.put(date.toString(), String.valueOf(amount)));
        String key = key(metric);
        // 2. 批量写入 Hash 字段
        redisUtil.hashPutAll(key, fields);
        // 3. 清理窗口外字段
        pruneExpiredFields(key);
        // 4. 设置缓存过期时间
        redisUtil.expire(key, CACHE_TTL);
    }

    /** 仅失效某指标维度的某一天。 */
    public void invalidate(DashboardTrendMetric metric, LocalDate businessDate) {
        if (businessDate != null) {
            redisUtil.hashDelete(key(metric), List.of(businessDate.toString()));
        }
    }

    /** 清理窗口外字段，仅保留最近 RETAIN_DAYS 天。 */
    private void pruneExpiredFields(String key) {
        // 1. 计算需要保留的最早日期
        LocalDate retainFrom = LocalDate.now().minusDays(RETAIN_DAYS - 1L);
        // 2. 获取所有Hash字段
        Set<String> fields = redisUtil.hashKeys(key);
        // 3. 过滤出需要删除的字段
        List<String> expiredFields = fields.stream().filter(field -> shouldRemove(field, retainFrom)).toList();
        // 4. 删除需要删除的字段
        redisUtil.hashDelete(key, expiredFields);
    }

    /** 判断是否需要删除字段。 */
    private boolean shouldRemove(String field, LocalDate retainFrom) {
        try {
            return LocalDate.parse(field).isBefore(retainFrom);
        } catch (RuntimeException exception) {
            log.warn("工作台趋势缓存存在非法日期字段，将删除 key={} field={}", CACHE_KEY_PREFIX, field);
            return true;
        }
    }

    /** 生成指标维度的缓存键。 */
    private String key(DashboardTrendMetric metric) {
        return CACHE_KEY_PREFIX + metric.name().toLowerCase();
    }
}