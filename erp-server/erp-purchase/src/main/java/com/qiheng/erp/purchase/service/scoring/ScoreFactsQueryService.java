package com.qiheng.erp.purchase.service.scoring;

import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;
import com.qiheng.erp.purchase.domain.supplierscore.dto.FactsSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Set;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

/** 白天使用供应商质量增量，正常入库沿用已有交付分；产品质量窗口事实不进入 Redis。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreFactsQueryService {
    private final SupplierScoreFactsAggregationService aggregationService;
    private final SupplierQualityAmountCacheService qualityCacheService;
    private final RedissonClient redissonClient;

    /**
     * 为凌晨或手工重算读取同一数据库快照，并刷新供应商质量金额缓存。
     *
     * @param supplierId 供应商 ID
     * @param businessDate 上海时区的评分业务日期
     * @return 质量、交付及产品质量金额的完整事实快照
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FactsSnapshot queryFacts(Long supplierId, LocalDate businessDate) {
        RLock lock = redissonClient.getLock(SupplierScoreRedisKeys.lockKey(supplierId));
        boolean acquired = false;
        try {
            // 读取前加锁，避免旧全量快照覆盖已经完成的白天增量；外层重算锁可重入。
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException("供应商事实重建锁繁忙，请重试");
            }
            return queryDatabaseFacts(supplierId, businessDate);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("供应商事实重建被中断", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /**
     * 读取同一数据库快照但不覆盖白天增量缓存。
     *
     * @param supplierId 供应商 ID
     * @param businessDate 上海时区的评分业务日期
     * @return 完整质量与交付事实快照
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FactsSnapshot queryFactsWithoutCache(Long supplierId, LocalDate businessDate) {
        var quality = aggregationService.queryQualityFacts(supplierId, businessDate);
        var delivery = aggregationService.queryDeliveryFacts(supplierId, businessDate);
        return snapshot(supplierId, quality, delivery);
    }

    /**
     * 在外层供应商锁和评分事务中处理完整合并订单，不因多单合并退回全量历史查询。
     * 质量冷启动或跨日校正返回完整产品事实，并登记全量已经包含的订单，避免重复累加。
     *
     * @param supplierId 目标供应商 ID
     * @param businessDate 上海时区消费日期
     * @param orderIds 本批首次完全入库的采购单集合
     * @return 供应商质量及受影响产品质量事实；全量回退时同时包含交付事实
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public FactsSnapshot queryInboundFacts(Long supplierId, LocalDate businessDate, Collection<Long> orderIds) {
        // 1. 检查缓存是否过期或缺失, 不存在则从数据库查询完整事实
        if (qualityCacheService.read(supplierId, businessDate) == null) {
            return queryDatabaseFacts(supplierId, businessDate);
        }
        SupplierScoreFactsAggregationService.CompletedOrderFacts completed;
        try {
            // 2. 查询供应商质量贡献
            completed = aggregationService.queryQualityContributions(supplierId, orderIds, businessDate);
        } catch (IllegalArgumentException e) {
            log.info("入库合并事件不适合当日增量，使用完整窗口校正 supplierId={} businessDate={}", supplierId, businessDate);
            return queryDatabaseFacts(supplierId, businessDate);
        }
        if (completed.orders().values().stream().anyMatch(contribution -> contribution.skippedOrders() > 0)) {
            // 异常订单可能曾计入旧交付分，整单跳过时同步校正质量与交付事实。
            return queryDatabaseFacts(supplierId, businessDate);
        }
        // 3. 累加供应商质量贡献
        for (var entry : completed.orders().entrySet()) {
            var contribution = entry.getValue();
            if (contribution.validOrders() == 0){
                continue;
            }
            var result = qualityCacheService.incrementCompletedOrder(supplierId, entry.getKey(), businessDate,
                    new SupplierQualityAmountCacheService.Amounts(contribution.qualifiedAmountRaw(), contribution.defectiveAmountRaw()));
            if (result == SupplierQualityAmountCacheService.IncrementResult.CACHE_MISS) {
                // 缓存恰好过期或被破坏时完整重建；新基线已经包含整批订单，不能继续增量。
                return queryDatabaseFacts(supplierId, businessDate);
            }
        }
        // 4. 从缓存查询供应商质量总额
        var totals = qualityCacheService.read(supplierId, businessDate);
        if (totals == null) {
            // 缓存恰好过期或被破坏时完整重建；新基线已经包含整批订单，不能继续增量。
            return queryDatabaseFacts(supplierId, businessDate);
        }
        // 5. 查询影响的产品的质量事实
        var quality = aggregationService.queryQualityFacts(supplierId, businessDate, completed.supplierProductIds());
        // 6. 正常完全入库沿用已落库交付分，不再读取金额缓存或扫描完整交付窗口。
        FactsSnapshot snapshot = snapshot(supplierId, quality, null);
        snapshot.setFullQualityRebuild(false);
        snapshot.setAffectedSupplierProductIds(completed.supplierProductIds());
        snapshot.setQualifiedAmountRaw(totals.qualifiedRaw());
        snapshot.setDefectiveAmountRaw(totals.defectiveRaw());
        snapshot.setSource("CACHE_AND_DB");
        return snapshot;
    }

    /** 组装完整质量与交付事实，将质量总额和今日已计入订单一起缓存。 */
    private FactsSnapshot queryDatabaseFacts(Long supplierId, LocalDate businessDate) {
        // 1. 从数据库查询完整质量与交付事实，仅刷新供应商质量缓存。
        var quality = aggregationService.queryQualityFacts(supplierId, businessDate);
        var delivery = aggregationService.queryDeliveryFacts(supplierId, businessDate);
        FactsSnapshot snapshot = snapshot(supplierId, quality, delivery);
        // 2. 缓存质量总额和今日已计入订单
        cache(supplierId, businessDate, new SupplierQualityAmountCacheService.Amounts(
                quality.qualifiedAmountRaw(), quality.defectiveAmountRaw()), quality.includedTodayOrderIds());
        return snapshot;
    }

    /** 组装质量事实；交付事实缺省时明确沿用已有分数，不能把缺省金额当成零。 */
    private FactsSnapshot snapshot(Long supplierId,
                                   SupplierScoreFactsAggregationService.QualityFacts quality,
                                   SupplierScoreFactsAggregationService.DeliveryFacts delivery) {
        FactsSnapshot snapshot = new FactsSnapshot();
        snapshot.setSupplierId(supplierId);
        snapshot.setQualifiedAmountRaw(quality.qualifiedAmountRaw());
        snapshot.setDefectiveAmountRaw(quality.defectiveAmountRaw());
        snapshot.setProductQualityAmounts(quality.productAmounts());
        snapshot.setNeedDeliveryRecalculation(delivery != null);
        if (delivery != null) {
            snapshot.setDueAmount(delivery.dueAmountCents());
            snapshot.setPenaltyAmount(delivery.penaltyAmountCents());
            snapshot.setPenaltyAmountRaw(delivery.penaltyAmountRaw());
            snapshot.setSkippedDeliveryOrders(delivery.skippedOrders());
        }
        snapshot.setSkippedQualityOrders(quality.skippedOrders());
        snapshot.setSource("DB");
        return snapshot;
    }

    /**
     * 优先读取当日质量总额缓存，缺失时持供应商锁从数据库重建。
     *
     * @param supplierId 供应商 ID
     * @param businessDate 上海时区的评分业务日期
     * @return 供应商合格与不合格原始金额总额
     */
    public SupplierQualityAmountCacheService.Amounts queryQualityTotals(Long supplierId, LocalDate businessDate) {
        // 1. 优先读取缓存
        var cached = qualityCacheService.read(supplierId, businessDate);
        if (cached != null) {
            return cached;
        }
        // 2. 缓存缺失时，持供应商锁从数据库重建
        RLock lock = redissonClient.getLock(SupplierScoreRedisKeys.lockKey(supplierId));
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException("质量缓存重建锁繁忙，请重试");
            }
            // 3. 再次读取缓存，避免重复重建
            cached = qualityCacheService.read(supplierId, businessDate);
            return cached == null ? rebuildQuality(supplierId, businessDate) : cached;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("质量缓存重建被中断", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /**
     * 将当日完全入库订单的质量金额去重追加到缓存；不负责评分落库。
     * 由首次完全入库的 MQ 消费在入库事务提交后调用。
     *
     * @param orderId 当日首次完全入库的采购单 ID
     * @param businessDate 上海时区的评分业务日期
     * @return 追加或冷启动重建后的供应商质量金额总额
     */
    public SupplierQualityAmountCacheService.Amounts applyCompletedOrder(Long orderId, LocalDate businessDate) {
        // 1. 查询订单贡献金额
        var contribution = aggregationService.queryQualityContribution(orderId, businessDate);
        Long supplierId = contribution.supplierId();
        RLock lock = redissonClient.getLock(SupplierScoreRedisKeys.lockKey(supplierId));
        boolean acquired = false;
        try {
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
            if (!acquired) {
                throw new IllegalStateException("质量增量重算锁繁忙，请重试");
            }
            // 2. 再次读取缓存，避免重复重建
            var cached = qualityCacheService.read(supplierId, businessDate);
            // 2.1 确认缓存是否存在
            if (cached == null) {
                return rebuildIncludingOrder(supplierId, orderId, businessDate);
            }
            // 2.2 确认订单贡献金额是否有效
            if (contribution.validOrders() == 0) {
                return cached;
            }
            // 2.3 确认缓存是否包含当前订单
            var result = qualityCacheService.incrementCompletedOrder(supplierId, orderId, businessDate,
                    new SupplierQualityAmountCacheService.Amounts(contribution.qualifiedAmountRaw(), contribution.defectiveAmountRaw()));
            // 2.4 如果是缓存过期, 或缺失必要字段,或者版本不匹配, 则重建缓存
            if (result == SupplierQualityAmountCacheService.IncrementResult.CACHE_MISS) {
                return rebuildIncludingOrder(supplierId, orderId, businessDate);
            }
            var updated = qualityCacheService.read(supplierId, businessDate);
            if (updated == null) throw new IllegalStateException("质量增量更新后不可读取");
            return updated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("质量增量被中断", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /** 从完整窗口重建质量总额，同时记录今日已经包含的订单。 */
    private SupplierQualityAmountCacheService.Amounts rebuildQuality(Long supplierId, LocalDate date) {
        // 1. 查询供应商质量事实
        var facts = aggregationService.queryQualityFacts(supplierId, date);
        // 2. 计算合格与不合格原始金额总额
        var amounts = new SupplierQualityAmountCacheService.Amounts(facts.qualifiedAmountRaw(), facts.defectiveAmountRaw());
        // 3. 缓存写入失败只记录告警，不丢弃已经取得的有效数据库事实
        cache(supplierId, date, amounts, facts.includedTodayOrderIds());
        return amounts;
    }

    /** 冷启动已包含当前订单，补充去重标记而不再次追加金额。 */
    private SupplierQualityAmountCacheService.Amounts rebuildIncludingOrder(Long supplierId, Long orderId, LocalDate date) {
        // 1. 从完整窗口重建质量总额
        var amounts = rebuildQuality(supplierId, date);
        // 2. 补充去重标记不再次追加金额
        qualityCacheService.incrementCompletedOrder(supplierId, orderId, date,
                new SupplierQualityAmountCacheService.Amounts(BigInteger.ZERO, BigInteger.ZERO));
        return amounts;
    }

    /** 缓存写入失败只记录告警，不丢弃已经取得的有效数据库事实。 */
    private void cache(Long supplierId, LocalDate date, SupplierQualityAmountCacheService.Amounts amounts,
                       Set<Long> includedTodayOrderIds) {
        try {
            qualityCacheService.overwrite(supplierId, date, amounts, includedTodayOrderIds);
        } catch (RuntimeException e) {
            log.warn("DB 评分事实有效，Redis 失败不阻断本次计算 supplierId={}", supplierId);
        }
    }

}
