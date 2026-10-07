package com.qiheng.erp.purchase.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.constant.SystemExceptionConstants;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
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
import java.util.ArrayList;
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
    /** 汇总上报 detailSummary 中最多携带的失败供应商 ID 数，避免极端故障刷爆待办摘要 */
    private static final int FAILED_ID_SAMPLE_LIMIT = 5;
    private static final String QUOTE_TASK_NO = "supplier-score-quote-expired";
    private static final String DAILY_TASK_NO = "supplier-score-daily-reconciliation";
    private final SupplierScoreRecalculateService recalculateService;
    private final SupplierMapper supplierMapper;
    private final SupplierProductMapper supplierProductMapper;
    private final SystemExceptionMqPublisher systemExceptionMqPublisher;
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
        // 3. 对每个供应商重算评分，失败计数汇总上报（有 02:00 全量校正兜底，severity=LOW）
        int failedCount = 0;
        List<Long> failedIds = new ArrayList<>();
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
                // 中断也需上报：已 done 部分失败的汇总不能因 return 丢失，应用关闭期间仍能让工作台待办感知
                if (failedCount > 0) {
                    systemExceptionMqPublisher.publishJobFailure(QUOTE_TASK_NO,
                            "报价到期评分重算被中断，已完成部分失败 " + failedCount + " 家(supplierId: " + failedIds + ")",
                            "应用正在关闭；等待 02:00 每日全量校正自动兜底",
                            SystemExceptionConstants.SEVERITY_LOW);
                }
                return;
            } catch (RuntimeException e) {
                failedCount++;
                if (failedIds.size() < FAILED_ID_SAMPLE_LIMIT) {
                    failedIds.add(supplierId);
                }
                log.error("报价到期重算失败，待每日校正 supplierId={}", supplierId, e);
            } finally {
                if (acquired && lock.isHeldByCurrentThread()) lock.unlock();
            }
        }
        // 5. 汇总上报一条，绝不 per-supplier 发送，避免环境级故障刷爆工作台待办
        if (failedCount > 0) {
            systemExceptionMqPublisher.publishJobFailure(QUOTE_TASK_NO,
                    "报价到期评分重算失败 " + failedCount + " 家(supplierId: " + failedIds + ")",
                    "等待 02:00 每日全量校正自动兜底；连续多日出现时检查 MQ 消费与评分事实查询",
                    SystemExceptionConstants.SEVERITY_LOW);
        }
    }

    /** 按固定业务日期分页校正启用供应商的评分，单个供应商失败不阻断后续处理；失败汇总上报待次日自愈。 */
    @Scheduled(cron = "0 0 2 * * ?", zone = "Asia/Shanghai")
    public void dailyReconciliation() {
        LocalDate date = LocalDate.now(BUSINESS_ZONE);
        RLock lock = redissonClient.getLock("supplier:score:daily:lock");
        boolean acquired = false;
        long success = 0, failed = 0, cursor = 0;
        List<Long> failedIds = new ArrayList<>();
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
                        if (failedIds.size() < FAILED_ID_SAMPLE_LIMIT) {
                            failedIds.add(supplier.getId());
                        }
                        log.error("每日评分校正被中断 supplierId={} businessDate={}", supplier.getId(), date, e);
                        return;
                    } catch (RuntimeException e) {
                        failed++;
                        if (failedIds.size() < FAILED_ID_SAMPLE_LIMIT) {
                            failedIds.add(supplier.getId());
                        }
                        log.error("每日评分校正失败，需补跑 supplierId={} businessDate={}", supplier.getId(), date, e);
                    } finally {
                        if (supplierLocked && supplierLock.isHeldByCurrentThread()) supplierLock.unlock();
                    }
                }
                cursor = page.getLast().getId();
                if (page.size() < PAGE_SIZE) break;
            }
            log.info("每日评分校正完成 businessDate={} success={} failed={}", date, success, failed);
            // 3. 失败汇总上报一条（次日 02:00 自动再校正兜底，severity=LOW）
            if (failed > 0) {
                systemExceptionMqPublisher.publishJobFailure(DAILY_TASK_NO,
                        "每日评分校正失败 " + failed + " 家(supplierId: " + failedIds + ")，success=" + success,
                        "次日 02:00 自动再校正；连续失败时检查 MQ 消费与 Redis pending",
                        SystemExceptionConstants.SEVERITY_LOW);
            }
        } catch (InterruptedException e) {
            // 整体中断说明调度线程被关闭打断；恢复中断位并抛出，不上报（应用关闭场景 MQ 可能已停）。
            // 但已 done 的失败仍需汇总上报，避免中断路径丢失工作台感知。
            Thread.currentThread().interrupt();
            log.error("每日评分校正被中断 businessDate={}", date, e);
            if (failed > 0) {
                systemExceptionMqPublisher.publishJobFailure(DAILY_TASK_NO,
                        "每日评分校正被中断，已完成部分失败 " + failed + " 家(supplierId: " + failedIds + ")",
                        "应用正在关闭；下次 02:00 自动再校正",
                        SystemExceptionConstants.SEVERITY_LOW);
            }
            throw new IllegalStateException("每日评分校正被中断", e);
        } catch (Exception e) {
            // 整体失败说明兜底链路本身断了，这是最严重的一档，必须上报
            log.error("每日评分校正整体失败 businessDate={}", date, e);
            systemExceptionMqPublisher.publishJobFailure(DAILY_TASK_NO,
                    "每日评分校正整体失败：" + e.getClass().getSimpleName(),
                    "检查 MySQL/Redis 可用性后人工补跑校正；校正链路断裂期间评分可能漂移",
                    SystemExceptionConstants.SEVERITY_HIGH);
            throw new IllegalStateException("每日评分校正整体失败", e);
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
