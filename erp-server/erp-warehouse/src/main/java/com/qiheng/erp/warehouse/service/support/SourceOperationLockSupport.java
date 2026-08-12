package com.qiheng.erp.warehouse.service.support;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.TimeUnit;

/**
 * 来源单操作锁（基于 Redisson 的分布式锁）。
 *
 * <p>作用：让"来源单取消/编辑"与"仓储 confirmBill（出入库确认）"这两个都会读改写来源单状态
 * 并触碰库存的操作，按来源单 ID 串行化执行，防止 TOCTOU 导致状态机错乱
 * （取消已确认的出库、或确认已取消的出库）。</p>
 * <p>为什么不用乐观锁兜底：这两个操作的副作用链长（取消工作单、释放/扣减库存、回写来源单、
 * 可能生成新工作单），若靠乐观锁在末尾 UPDATE 来源单时才检测冲突，失败方已执行全部副作用，
 * 回滚成本高且持有库存行锁时间长。分布式锁把冲突检测前移到事务开头，后到的在锁内重查
 * 发现状态已变即直接拒绝，根本不进入副作用阶段，零回滚成本。</p>
 *
 * <p>锁粒度：按 sourceType + sourceId，单张来源单级别，不影响其他订单的并发。</p>
 */
@Component
public class SourceOperationLockSupport {

    private final RedissonClient redissonClient;

    public SourceOperationLockSupport(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 获取来源单操作锁，并在当前事务完成后释放。
     * 不指定固定租约，使用 Redisson watchdog 续期，避免慢事务提前失锁。
     */
    public void acquire(String sourceType, Long sourceId) {
        if (sourceType == null || sourceId == null) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "来源单锁参数不能为空");
        }
        RLock lock = redissonClient.getLock("warehouse:source-operation:" + sourceType + ":" + sourceId);
        boolean acquired;
        try {
            acquired = lock.tryLock(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "操作被中断");
        }
        if (!acquired) {
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "来源单正在处理中，请稍后重试");
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            lock.unlock();
            throw new BizException(ErrorCode.OPERATION_FAILED.getCode(), "来源单操作锁必须在事务内获取");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });
    }
}