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
 * 来源单操作锁。
 *
 * <p>来源单取消与仓库确认必须在任何库存、工作单或来源单写入之前竞争同一把锁，
 * 避免两条事务按不同顺序持有库存和来源单行锁。</p>
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
