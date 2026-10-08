package com.qiheng.erp.dashboard.cache;

import com.qiheng.erp.common.event.dashboard.TopProductRankAdjustEvent;
import com.qiheng.erp.common.event.dashboard.TopProductRankAdjustEvent.RankItemInput;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 工作台 TOP 商品排行 Redis 缓存。
 *
 * <p>维护近 30 天销售金额与数量排行：白天靠业务事件增量加减分（{@link TransactionalEventListener}
 * 事务提交后触发，O(log N)），凌晨全量重建（SQL 查近 30 天真实数据）兜底修正。
 * 商品属性（编码、名称、库存）不入缓存，读取时按需实时查数据库。</p>
 *
 * <p>四个 Lua 脚本保证关键路径原子性：
 * <ul>
 *   <li>{@link #ADJUST_OR_BUFFER_SCRIPT}：Lua 内一脚本判断重建状态 + 写缓冲或加减分，
 *       同一脚本内判定与写入，避免边界遗漏</li>
 *   <li>{@link #REPLAY_AND_CLOSE_REBUILD_SCRIPT}：回放缓冲 + 关闭重建窗口，
 *       保证最后一批缓冲回放与状态关闭之间没有遗漏边界</li>
 *   <li>{@link #READ_TOP_SCRIPT}：一次性读 ZSet 前 N 名 + 对应数量，金额数量同一时刻快照</li>
 *   <li>{@link #SWAP_SCRIPT}：双 EXISTS 校验后原子切换；任一临时 key 缺失则不切换并清残留，
 *       避免"ZSet 写成功 Hash 写失败"导致的金额数量数据不一致</li>
 * </ul></p>
 *
 * <p>重建期间通过 Redisson watchdog 锁 + Redis {@code rebuilding} 标记双重保护：
 * 多实例只让一个跑，进程崩溃自动释放锁（watchdog 续期），即使 Redis 异常下次重建也能恢复。</p>
 *
 * @author Li
 * @since 2026-09-02
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TopProductRankCache {

    /** 近 30 天累计金额，Sorted Set，score = 金额（分值），member = 商品 ID */
    private static final String AMOUNT_KEY = "dashboard:top-product:sales-amount";

    /** 近 30 天累计销售数量，Hash，field = 商品 ID（字符串），value = 数量 */
    private static final String QTY_KEY = "dashboard:top-product:sales-qty";

    /** 凌晨临时 key，写入完成后原子 RENAME 切换，避免读空数据 */
    private static final String NEW_SUFFIX = ":new";

    /** 重建状态标记：存在 = 正在重建；增量事件一律缓冲；重建结束 DEL 该 key */
    private static final String REBUILDING_KEY = "dashboard:top-product:rebuilding";

    /** 重建窗口内的跨实例增量缓冲（List 队列）；重建结束被 LPOOP 回放后清空 */
    private static final String REBUILD_BUFFER_KEY = "dashboard:top-product:rebuild-buffer";

    /** 多实例互斥的分布式锁 key */
    private static final String REBUILD_LOCK_KEY = "dashboard:top-product:rebuild-lock";

    /** 滑动窗口天数 */
    static final int WINDOW_DAYS = 30;

    /** 与 DashboardDailySnapshotJob / TrendCacheInvalidationListener 对齐：3 次重试、1s/2s 递增 */
    private static final int MAX_RETRY = 3;
    private static final long BASE_DELAY_MS = 1000L;

    /**
     * 增量写入网关：Lua 脚本内判定重建状态，命中则把整个事件批次推入共享 Redis 队列，
     * 未命中则走原子加减分。状态判定与写入在同一脚本内执行，杜绝
     * "判断后到写入前状态被切换"的边界遗漏。
     * <ul>
     *   <li>KEYS[1] = amountKey, KEYS[2] = qtyKey</li>
     *   <li>KEYS[3] = rebuildingKey（存在即处于重建），KEYS[4] = bufferKey</li>
     * </ul>
     * 缓冲时返回 0；正常加减时返回 ARGV/3（处理条数）。
     */
    private static final DefaultRedisScript<Long> ADJUST_OR_BUFFER_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[3]) == 1 then "
                    + "redis.call('RPUSH', KEYS[4], cjson.encode(ARGV)) "
                    + "return 0 "
                    + "end "
                    + "for i = 1, #ARGV, 3 do "
                    + "local member = ARGV[i] "
                    + "local amountDelta = tonumber(ARGV[i + 1]) "
                    + "local qtyDelta = tonumber(ARGV[i + 2]) "
                    + "local newScore = tonumber(redis.call('ZINCRBY', KEYS[1], amountDelta, member)) "
                    + "if newScore <= 0 then "
                    + "redis.call('ZREM', KEYS[1], member) "
                    + "redis.call('HDEL', KEYS[2], member) "
                    + "else "
                    + "redis.call('HINCRBY', KEYS[2], member, qtyDelta) "
                    + "end "
                    + "end "
                    + "return #ARGV / 3",
            Long.class);

    /**
     * 回放缓冲并关闭重建窗口：Lua 单脚本内 LPOP 队列 → 逐条执行加减分 → 完成后 DEL 重建标记 + 队列。
     * 把状态切换 + 队列消费放在一个原子脚本，杜绝"最后几条事件与状态切换之间遗漏"。
     * <ul>
     *   <li>KEYS[1] = amountKey, KEYS[2] = qtyKey, KEYS[3] = rebuildingKey, KEYS[4] = bufferKey</li>
     * </ul>
     * 返回回放的事件批次条数。
     */
    private static final DefaultRedisScript<Long> REPLAY_AND_CLOSE_REBUILD_SCRIPT = new DefaultRedisScript<>(
            "local replayed = 0 "
                    + "while true do "
                    + "local encoded = redis.call('LPOP', KEYS[4]) "
                    + "if encoded == false then break end "
                    + "local args = cjson.decode(encoded) "
                    + "for i = 1, #args, 3 do "
                    + "local member = args[i] "
                    + "local amountDelta = tonumber(args[i + 1]) "
                    + "local qtyDelta = tonumber(args[i + 2]) "
                    + "local newScore = tonumber(redis.call('ZINCRBY', KEYS[1], amountDelta, member)) "
                    + "if newScore <= 0 then "
                    + "redis.call('ZREM', KEYS[1], member) "
                    + "redis.call('HDEL', KEYS[2], member) "
                    + "else "
                    + "redis.call('HINCRBY', KEYS[2], member, qtyDelta) "
                    + "end "
                    + "end "
                    + "replayed = replayed + 1 "
                    + "end "
                    + "redis.call('DEL', KEYS[3]) "
                    + "redis.call('DEL', KEYS[4]) "
                    + "return replayed",
            Long.class);

    /**
     * 一次性读取 Lua：单脚本内 ZREVRANGE 取前 N 名 + 对应数量 HGET，
     * 金额与数量同一时刻读取，并发增量写入也不会出现"金额新/数量旧"错配。
     * 返回 List<List>：每个子列表 [member(String), score(String), qty(String)]。
     */
    private static final DefaultRedisScript<List> READ_TOP_SCRIPT = new DefaultRedisScript<>(
            "local n = tonumber(ARGV[1]) "
                    + "local top = redis.call('ZREVRANGE', KEYS[1], 0, n - 1, 'WITHSCORES') "
                    + "local result = {} "
                    + "for i = 1, #top, 2 do "
                    + "local member = top[i] "
                    + "local score = top[i + 1] "
                    + "local qty = redis.call('HGET', KEYS[2], member) "
                    + "if qty == false then qty = '0' end "
                    + "table.insert(result, {member, score, qty}) "
                    + "end "
                    + "return result",
            List.class);

    /**
     * 重建原子切换 Lua：双 EXISTS 校验后切换——任一临时 key 缺失则放弃切换并清残留，
     * 避免"ZSet 写成功 Hash 写失败"导致金额在但数量全丢的不一致状态。
     *
     * <p>"部分写入"的典型场景：
     * <ul>
     *   <li>{@code redisTemplate.opsForZSet().add(...)} 成功但
     *       {@code redisTemplate.opsForHash().putAll(...)} 失败（进程被 kill / Redis 连接闪断 / OOM）</li>
     *   <li>两个独立 Redis 命令不在同一事务里,中间任何失败都会让 amountNew 与 qtyNew 不一致</li>
     * </ul>
     * 放弃切换 + DEL 临时 key + 返回 0,Java 层抛 IllegalStateException 由 doRebuildSafely 走异常分支消费缓冲队列。
     *
     * <ul>
     *   <li>KEYS[1] = amountNew, KEYS[2] = qtyNew</li>
     *   <li>KEYS[3] = amountFinal, KEYS[4] = qtyFinal</li>
     * </ul>
     * 返回 1 表示已切换；返回 0 表示部分写入放弃切换（调用方应走异常分支消费队列）。
     */
    private static final DefaultRedisScript<Long> SWAP_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('EXISTS', KEYS[1]) == 1 and redis.call('EXISTS', KEYS[2]) == 1 then "
                    + "redis.call('RENAME', KEYS[1], KEYS[3]) "
                    + "redis.call('RENAME', KEYS[2], KEYS[4]) "
                    + "return 1 "
                    + "end "
                    + "redis.call('DEL', KEYS[1]) "
                    + "redis.call('DEL', KEYS[2]) "
                    + "return 0",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final RedissonClient redissonClient;
    private final SystemExceptionMqPublisher systemExceptionMqPublisher;

    /**
     * 监听销售审核 / 取消与退货单审核 / 取消事件：
     * 业务事务提交后才更新 Redis，避免回滚污染缓存。
     * 原单据 businessDate 早于 30 天窗口起点则跳过，由凌晨重建 SQL 兜底。
     * 写动作下沉到 {@link #ADJUST_OR_BUFFER_SCRIPT}：重建窗口内自动缓冲到 Redis 队列。
     *
     * <p>Redis 抖动导致 Lua 脚本执行失败时执行线性重试，重试耗尽后上报系统异常，
     * 异常不再向上抛出——{@code @TransactionalEventListener(AFTER_COMMIT)} 阶段事务
     * 已提交，监听器异常原本也会被 Spring 默认 ErrorHandler 吞掉仅留 ERROR 日志；
     * 改造后让"增量失败可观测"，凌晨 SQL 重建仍能按窗口起算重算覆盖漂移区间。</p>
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAdjust(TopProductRankAdjustEvent event) {
        if (event.items() == null || event.items().isEmpty()) {
            return;
        }
        // 原单据 businessDate 早于 30 天窗口起点则跳过，由凌晨 SQL 兜底
        if (event.businessDate() != null
                && event.businessDate().isBefore(LocalDate.now().minusDays(WINDOW_DAYS - 1L))) {
            return;
        }
        applyOrBufferWithRetry(event.items(), event.deltaSign());
    }

    /**
     * 线性重试执行 Lua 脚本；耗尽后上报系统异常并吞掉异常（不让 AFTER_COMMIT 阶段影响业务响应）。
     *
     * @param items 商品行集合
     * @param sign 加减分符号（+1 审核、-1 取消）
     */
    private void applyOrBufferWithRetry(List<RankItemInput> items, int sign) {
        Exception last = null;
        for (int attempt = 1; attempt <= MAX_RETRY; attempt++) {
            try {
                applyOrBuffer(items, sign);
                return;
            } catch (Exception ex) {
                last = ex;
                log.error("TOP 排行增量更新第{}/{}次失败 items={} deltaSign={}",
                        attempt, MAX_RETRY, items.size(), sign, ex);
                if (attempt < MAX_RETRY) {
                    try {
                        // 1s / 2s 递增延迟，与 DashboardDailySnapshotJob.retryStep 一致
                        Thread.sleep(BASE_DELAY_MS * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("TOP 排行增量更新重试等待被中断，停止重试 items={}", items.size());
                        break;
                    }
                }
            }
        }
        // 重试耗尽：上报系统异常，运维可在工作台看到并人工评估是否需要重跑；
        // 凌晨 SQL 重建仍能覆盖漂移区间。
        systemExceptionMqPublisher.publishSystemError(
                new TopRankUpdateFailedException(items.size(), sign, last));
    }

    /** Lua 脚本：重建期间缓冲、其他时段原子加减分 */
    private void applyOrBuffer(List<RankItemInput> items, int sign) {
        redisTemplate.execute(ADJUST_OR_BUFFER_SCRIPT,
                List.of(AMOUNT_KEY, QTY_KEY, REBUILDING_KEY, REBUILD_BUFFER_KEY),
                buildAdjustArgs(items, sign));
    }

    private Object[] buildAdjustArgs(List<RankItemInput> items, int sign) {
        Object[] args = new Object[items.size() * 3];
        int idx = 0;
        for (RankItemInput item : items) {
            args[idx++] = String.valueOf(item.productId());
            args[idx++] = String.valueOf(sign * nullSafe(item.amount()));
            args[idx++] = String.valueOf(sign * nullSafe(item.quantity()));
        }
        return args;
    }

    /**
     * 凌晨全量重建：抢分布式锁（watchdog 模式防多实例并发）→ 写临时 key → Lua 原子切换 →
     * Lua 回放缓冲队列并 DEL 重建标记。
     *
     * <p>异常契约：
     * <ul>
     *   <li>{@link InterruptedException}：锁等待被中断,返回 FAILED + 保留中断标记</li>
     *   <li>{@link RuntimeException}：SQL/Redis 环境级故障穿透 rebuild 抛出,
     *       由 {@link TopProductRankRefresher#refresh} catch 后上报 SEVERITY_HIGH
     *       （区别于 FAILED 路径的 SEVERITY_MEDIUM）；
     *       {@code DashboardTopProductLoader.triggerRebuild} 也会捕获并 log warn,
     *       因为脏数据 / 冷启动场景下走非 Redisson 锁内部路径的调用方不一定要上报</li>
     * </ul>
     *
     * <p>关于并发去重：本方法由 {@code DashboardTopProductLoader} 在脏数据/冷启动场景调用时,
     * 锁竞争由 Redisson 锁天然处理（多实例并发触发,只有一个能拿到锁跑 supplier,
     * 其他实例立即返回 SKIPPED_LOCK_BUSY,用户刷新即可）。无需额外 dirty 标记。</p>
     *
     * @return 重建结果（成功 / 被其他实例抢占 / 失败），供 caller 区分日志与重试策略
     */
    public RebuildOutcome rebuild(Supplier<List<RankEntry>> entriesSupplier) {
        // 1. 抢分布式锁：watchdog 模式（leaseTime=-1 自动续期，进程崩溃自动释放）
        RLock lock = redissonClient.getLock(REBUILD_LOCK_KEY);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(0, -1L, TimeUnit.SECONDS);
            if (!acquired) {
                log.warn("其他实例正在重建 TOP 排行，本次跳过（凌晨 SQL 兜底）");
                return RebuildOutcome.SKIPPED_LOCK_BUSY;
            }
            doRebuildSafely(entriesSupplier.get());
            return RebuildOutcome.SUCCESS;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("重建锁等待被中断", ex);
            return RebuildOutcome.FAILED;
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    /**
     * 重建主流程：异常路径也要消费队列（写到当前缓存），避免队列遗留到下次重建被重复扣减。
     * 只有当 Redis 不可用连回放都失败时，才保留标记与队列等下次重建以新快照覆盖。
     */
    private void doRebuildSafely(List<RankEntry> entries) {
        // 1. 标记重建窗口
        redisTemplate.opsForValue().set(REBUILDING_KEY, "1");
        boolean replayedSuccessfully = false;
        try {
            // 2. 写临时 key + Lua 原子切换
            doRebuild(entries);
            // 3. 正常路径：回放 + 关闭标记
            replayAndClose();
            replayedSuccessfully = true;
        } catch (Exception ex) {
            // 4. 异常路径：仍需消费队列（即使缓存是旧数据，事件也该应用上）
            try {
                replayAndClose();
                replayedSuccessfully = true;
            } catch (Exception re) {
                // Redis 不可用，保留标记和队列等下次重建
                log.warn("TOP 商品排行重建失败且无法回放，保留 rebuilding 标记与队列", re);
                throw ex;
            }
            throw ex;
        } finally {
            // 正常回放失败的情况已在 catch 内处理；这里只在成功时记录日志
            if (replayedSuccessfully) {
                log.info("工作台 TOP 商品排行重建完成");
            }
        }
    }

    /** Lua 原子回放 + 关闭标记（无参版本，直接调用 REPLAY_AND_CLOSE_REBUILD_SCRIPT） */
    private void replayAndClose() {
        Long count = redisTemplate.execute(REPLAY_AND_CLOSE_REBUILD_SCRIPT,
                List.of(AMOUNT_KEY, QTY_KEY, REBUILDING_KEY, REBUILD_BUFFER_KEY));
        log.info("TOP 商品排行回放缓冲事件，条数={}", count == null ? 0 : count);
    }

    private void doRebuild(List<RankEntry> entries) {
        String amountNew = AMOUNT_KEY + NEW_SUFFIX;
        String qtyNew = QTY_KEY + NEW_SUFFIX;
        // 先清空临时 key，避免上次异常残留
        redisTemplate.delete(amountNew);
        redisTemplate.delete(qtyNew);
        // 构建临时 key：HashMap/HashSet 按负载因子 0.75 反推容量
        if (entries != null && !entries.isEmpty()) {
            int capacity = (int) (entries.size() / 0.75f) + 1;
            Set<ZSetOperations.TypedTuple<String>> tuples = new HashSet<>(capacity);
            Map<String, String> qtyMap = new HashMap<>(capacity);
            for (RankEntry entry : entries) {
                tuples.add(ZSetOperations.TypedTuple.of(
                        String.valueOf(entry.productId()), (double) entry.amount()));
                qtyMap.put(String.valueOf(entry.productId()), String.valueOf(entry.quantity()));
            }
            redisTemplate.opsForZSet().add(amountNew, tuples);
            redisTemplate.opsForHash().putAll(qtyNew, qtyMap);
        }
        // Lua 原子切换：双 EXISTS 校验后切换，任一缺失则放弃切换并清残留
        Long swapped = redisTemplate.execute(SWAP_SCRIPT,
                List.of(amountNew, qtyNew, AMOUNT_KEY, QTY_KEY));
        if (swapped != null && swapped == 0L) {
            // 部分写入不切换——抛异常由 doRebuildSafely 走异常分支消费队列
            throw new IllegalStateException("SWAP_SCRIPT 双 EXISTS 校验失败，放弃切换 amountNew/qtyNew 已清理");
        }
    }

    /**
     * 一次性读 TOP N（含数量）：Lua 脚本保证金额与数量同一时刻快照，避免并发错配。
     * limit ≤ 0 直接返回空，避免 Lua 中 0/-1 触发 ZREVRANGE 全量扫描。
     * 任一条目数值非法时抛 {@link RankDataCorruptedException}，由 DashboardTopProductLoader 捕获并触发防雪崩重建。
     */
    public List<RankEntry> readTop(int limit) {
        if (limit <= 0) {
            return Collections.emptyList();
        }
        Object raw = redisTemplate.execute(READ_TOP_SCRIPT,
                List.of(AMOUNT_KEY, QTY_KEY), String.valueOf(limit));
        if (!(raw instanceof List<?> rawList)) {
            // Lua 脚本异常返回 nil 或非 List 时静默返回空（罕见，不上报）
            return Collections.emptyList();
        }
        if (rawList.isEmpty()) {
            return Collections.emptyList();
        }
        List<RankEntry> result = new ArrayList<>(rawList.size());
        for (Object obj : rawList) {
            // row.size() < 3 是防御性检查：Lua 脚本设计上是三元组 [member, score, qty]，
            // 但理论上 Redis 协议异常或脚本被外部替换后可能返回非三元组
            if (!(obj instanceof List<?> row) || row.size() < 3) {
                continue;
            }
            String member = String.valueOf(row.get(0));
            String scoreStr = String.valueOf(row.get(1));
            String qtyStr = String.valueOf(row.get(2));
            try {
                result.add(new RankEntry(
                        parseLongStrict(member),
                        parseLongStrict(scoreStr),
                        parseLongStrict(qtyStr)));
            } catch (NumberFormatException ex) {
                // 任一字段无法解析 → 整批视为脏数据,抛 RankDataCorruptedException 由调用方处理
                throw new RankDataCorruptedException(member, scoreStr, qtyStr);
            }
        }
        return result;
    }

    private static long nullSafe(Long value) {
        return value == null ? 0L : value;
    }

    /**
     * 严格解析：失败抛 {@link NumberFormatException}（不返回 0 静默吞），
     * 由 readTop 转为 {@link RankDataCorruptedException} 让运维感知。
     */
    private static long parseLongStrict(String value) {
        return Long.parseLong(value);
    }

    /** 排行条目：来自凌晨重建 SQL 全量计算结果；基本类型 long 阻止 SQL 端 null 写入 */
    public record RankEntry(long productId, long amount, long quantity) {}

    /** 重建结果：让 caller 区分成功 / 被跳过 / 失败，按情况打不同日志与重试策略 */
    public enum RebuildOutcome {
        /** 重建完成，缓冲队列已回放，旧榜单已切换为新快照 */
        SUCCESS,
        /** 锁被其他实例占用，本次跳过；凌晨 SQL 兜底或下一轮重试 */
        SKIPPED_LOCK_BUSY,
        /** 重建失败（数据库 / Redis 不可用），需要重试或运维介入 */
        FAILED
    }
}
