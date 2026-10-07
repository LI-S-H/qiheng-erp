package com.qiheng.erp.purchase.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreRecalcContext;
import com.qiheng.erp.purchase.domain.supplierscore.enums.OperatorType;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import com.qiheng.erp.purchase.mapper.SupplierProductMapper;
import com.qiheng.erp.purchase.service.SupplierScoreRecalculateService;
import com.qiheng.erp.common.constant.SupplierScoreRedisKeys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.concurrent.TimeUnit;

/** 每日权威校正：分页处理供应商，单个失败不阻断后续供应商。 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "supplier-score.scheduled.enabled", havingValue = "true")
public class SupplierScoreScheduledJob {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int PAGE_SIZE = 100;
    private final SupplierScoreRecalculateService recalculateService;
    private final SupplierMapper supplierMapper;
    private final SupplierProductMapper supplierProductMapper;
    private final RedissonClient redissonClient;

    /** 每天只处理前一天到期的报价，漏跑由两点的每日全量校正补偿。 */
    @Scheduled(cron = "0 30 1 * * ?", zone = "Asia/Shanghai")
    public void scanExpiredQuotes() {
        LocalDate expiredDate = LocalDate.now(BUSINESS_ZONE).minusDays(1);
        Set<Long> supplierIds = new HashSet<>();
        long cursor = 0;
        while (true) {
            // 1. 分页查询所有到期的报价产品
            List<SupplierProduct> page = supplierProductMapper.selectList(new LambdaQueryWrapper<SupplierProduct>()
                    .select(SupplierProduct::getId, SupplierProduct::getSupplierId)
                    .eq(SupplierProduct::getQuoteValidUntil, expiredDate)
                    .eq(SupplierProduct::getStatus, 1).eq(SupplierProduct::getDeleted, 0)
                    .gt(SupplierProduct::getId, cursor).orderByAsc(SupplierProduct::getId)
                    .last("LIMIT " + PAGE_SIZE));
            if (page.isEmpty()) {
                break;
            }
            // 2. 从报价产品中提取供应商 ID
            for (SupplierProduct product : page){
                supplierIds.add(product.getSupplierId());
            }
            cursor = page.getLast().getId();
            if (page.size() < PAGE_SIZE) {
                break;
            }
        }
        // 3. 对每个供应商重算评分
        for (Long supplierId : supplierIds) {
            RLock lock = redissonClient.getLock(SupplierScoreRedisKeys.lockKey(supplierId));
            boolean acquired = false;
            try {
                acquired = lock.tryLock(5, TimeUnit.SECONDS);
                if (!acquired) {
                    throw new IllegalStateException("供应商评分锁繁忙");
                }
                // 4. 重算供应商以及所有报价产品的评分
                recalculateService.recalcPricesForSupplier(supplierId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("报价到期重算被中断 supplierId={}", supplierId, e);
                return;
            } catch (RuntimeException e) {
                log.error("报价到期重算失败，待每日校正 supplierId={}", supplierId, e);
            } finally {
                if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
            }
        }
    }

    /** 按固定业务日期分页校正启用供应商的评分，单个供应商失败不阻断后续处理。 */
    @Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Shanghai")
    public void dailyReconciliation() {
        LocalDate date = LocalDate.now(BUSINESS_ZONE);
        RLock lock = redissonClient.getLock("supplier:score:daily:lock");
        boolean acquired = false;
        long success = 0, failed = 0, cursor = 0;
        try {
            acquired = lock.tryLock(0, TimeUnit.SECONDS);
            if (!acquired) return;
            while (true) {
                // 1. 分页查询所有启用供应商
                List<Supplier> page = supplierMapper.selectList(new LambdaQueryWrapper<Supplier>()
                        .select(Supplier::getId).eq(Supplier::getStatus, 1)
                        .gt(Supplier::getId, cursor).orderByAsc(Supplier::getId).last("LIMIT " + PAGE_SIZE));
                if (page.isEmpty()) {
                    break;
                }
                // 2. 对每个供应商校正评分
                for (Supplier supplier : page) {
                    RLock supplierLock = redissonClient.getLock(SupplierScoreRedisKeys.lockKey(supplier.getId()));
                    boolean supplierLocked = false;
                    try {
                        // 单个供应商与 MQ 消费使用同一把外层锁，避免两条重算链路并发覆盖。
                        supplierLocked = supplierLock.tryLock(5, TimeUnit.SECONDS);
                        if (!supplierLocked) {
                            throw new IllegalStateException("供应商评分锁繁忙，留待下次校正");
                        }
                        recalculateService.recalcFactsForSupplier(context(supplier.getId(), date), date);
                        success++;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        failed++;
                        log.error("每日评分校正被中断 supplierId={} businessDate={}", supplier.getId(), date, e);
                        return;
                    } catch (RuntimeException e) {
                        failed++;
                        log.error("每日评分校正失败，需补跑 supplierId={} businessDate={}", supplier.getId(), date, e);
                    } finally {
                        if (supplierLocked && supplierLock.isHeldByCurrentThread()) supplierLock.unlock();
                    }
                }
                cursor = page.getLast().getId();
                if (page.size() < PAGE_SIZE) break;
            }
            log.info("每日评分校正完成 businessDate={} success={} failed={}", date, success, failed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("每日评分校正被中断", e);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    /** 构造每日校正上下文；有实际变更时由日志入口生成统一 SC 批次号。 */
    private ScoreRecalcContext context(Long supplierId, LocalDate date) {
        return ScoreRecalcContext.builder().supplierId(supplierId)
                .triggerType(TriggerType.DAILY_TRIGGER)
                .relatedSources(List.of()).mergedReason("每日事实校正，业务日期=" + date)
                .operatorType(OperatorType.SYSTEM.name())
                .operatorName("每日事实校正").executionMode("SCHEDULED").build();
    }
}
