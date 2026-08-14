package com.qiheng.erp.common.util;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 序列号生成器骨架。
 *
 * <p>编排「获取分布式锁 → 读取 Redis 序列 → 数据库回查初始化 → 自增 → 格式化 → 释放锁」
 * 的完整流程，子类只需提供 {@link SequenceDefinition} 和数据库回查函数即可复用。</p>
 */
@Slf4j
public abstract class AbstractSequenceGenerator {

    private static final long LOCK_WAIT_SECONDS = 5L;

    protected final StringRedisTemplate stringRedisTemplate;
    protected final RedissonClient redissonClient;

    private final String label;

    protected AbstractSequenceGenerator(StringRedisTemplate stringRedisTemplate,
                                        RedissonClient redissonClient,
                                        String label) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.redissonClient = redissonClient;
        this.label = label;
    }

    /**
     * 生成下一个序列号。
     *
     * @param definition                  序列号格式定义
     * @param maxExistingSequenceSupplier 数据库最大合法序号查询
     * @param exhaustedMessage            序号耗尽时的错误信息
     * @return 已格式化的序列号
     */
    protected String nextSequence(SequenceDefinition definition,
                                  LongSupplier maxExistingSequenceSupplier,
                                  String exhaustedMessage) {
        RLock lock = null;
        boolean locked = false;
        try {
            // 1. 获取分布式锁
            lock = redissonClient.getLock(definition.lockKey());
            // 不指定 leaseTime，交由 Redisson watchdog 在临界区内自动续期，避免长暂停导致锁失效。
            locked = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            if (!locked) {
                throw failure(label + "生成繁忙，请稍后重试");
            }
            // 2. 从 Redis 序列中获取当前序号
            ValueOperations<String, String> ops = stringRedisTemplate.opsForValue();
            long current = currentSequence(ops, definition, maxExistingSequenceSupplier);
            if (current >= definition.maxSequence()) {
                throw failure(exhaustedMessage);
            }
            // 3. 自增 Redis 序列值
            Long next = ops.increment(definition.redisKey());
            if (next == null || next < 1 || next > definition.maxSequence()) {
                throw failure(label + "序列异常");
            }
            // 4. 格式化
            return definition.formatPrefix() + String.format("%0" + definition.width() + "d", next);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(label + "生成被中断");
        } catch (BizException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.error("{}生成失败，redisKey={}", label, definition.redisKey(), exception);
            throw failure(label + "生成失败，请稍后重试");
        } finally {
            releaseLock(lock, locked);
        }
    }

    /**
     * 从 Redis 序列中获取当前序号。
     */
    private long currentSequence(ValueOperations<String, String> ops,
                                 SequenceDefinition definition,
                                 LongSupplier maxExistingSequenceSupplier) {
        // 1. 从 Redis 序列中获取当前序号
        String cachedValue = ops.get(definition.redisKey());
        if (cachedValue != null) {
            // 缓存存在，直接解析为 Long
            return parseSequence(cachedValue, definition.maxSequence());
        }
        // 2. 缓存不存在，从数据库查询最大序号
        long databaseMaxSequence = maxExistingSequenceSupplier.getAsLong();
        // 3. 验证数据库最大序号是否在有效范围内
        validateSequence(databaseMaxSequence, definition.maxSequence());
        // 4. 补充 Redis 序列值为最大序号
        Boolean initialized = ops.setIfAbsent(definition.redisKey(), String.valueOf(databaseMaxSequence));
        if (Boolean.TRUE.equals(initialized)) {
            return databaseMaxSequence;
        }
        // 5. 初始化失败说明别人抢先写入了，再读一次 Redis 取对方写的值
        String initializedValue = ops.get(definition.redisKey());
        if (initializedValue == null) {
            throw failure(label + "序列初始化失败");
        }
        return parseSequence(initializedValue, definition.maxSequence());
    }

    /**
     * 解析 Redis 序列值为 Long。
     */
    private long parseSequence(String value, long maxSequence) {
        try {
            long sequence = Long.parseLong(value);
            validateSequence(sequence, maxSequence);
            return sequence;
        } catch (NumberFormatException exception) {
            throw failure(label + "序列状态异常");
        }
    }

    /**
     * 验证序列是否在有效范围内。
     * 比如：位宽为 5，最大序号为 99999，序列 100000 为无效。
     */
    private void validateSequence(long sequence, long maxSequence) {
        if (sequence < 0 || sequence > maxSequence) {
            throw failure(label + "序列状态异常");
        }
    }

    /**
     * 释放分布式锁。
     */
    private void releaseLock(RLock lock, boolean locked) {
        // 1. 检查是否持有锁
        if (!locked) {
            return;
        }
        // 2. 释放锁
        try {
            if (!lock.isHeldByCurrentThread()) {
                throw failure(label + "锁已失效");
            }
            lock.unlock();
        } catch (BizException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.error("释放{}生成锁失败", label, exception);
            throw failure(label + "锁释放失败");
        }
    }

    /**
     * 构建失败异常。
     */
    protected BizException failure(String message) {
        return new BizException(ErrorCode.OPERATION_FAILED.getCode(), message);
    }
}