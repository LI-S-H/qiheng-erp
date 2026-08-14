package com.qiheng.erp.common.util;

import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.function.LongSupplier;

/**
 * 主数据编码生成器。
 *
 * <p>调用方以 {@link LongSupplier} 提供所属业务表的最大合法数字后缀，公共组件不依赖任何
 * 具体业务模块或 Mapper。Redis 序列丢失时，生成器在同一把分布式锁内完成数据库回查、缓存初始化
 * 和自增，避免 Redis 重启后重复分配既有编码。</p>
 */
@Component
public class CodeNoGenerator extends AbstractSequenceGenerator {

    private static final String LOCK_KEY_PREFIX = "code:lock:";

    public CodeNoGenerator(StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        super(stringRedisTemplate, redissonClient, "编码");
    }

    /**
     * 生成下一个主数据编码。
     * <p>Redis key 正常存在时，以 Redis 序列为准；仅当 key 缺失时调用
     * {@code maxExistingSequenceSupplier} 从数据库恢复序列。Redis、锁或数据库查询异常时
     * 会失败关闭，调用方无法取得编码，因而不会继续写入业务数据。</p>
     * @param definition 编码格式定义
     * @param maxExistingSequenceSupplier 当前业务表最大合法数字后缀查询
     * @return 已格式化的主数据编码
     */
    public String nextNo(CodeNoDefinition definition, LongSupplier maxExistingSequenceSupplier) {
        SequenceDefinition sequenceDefinition = new SequenceDefinition(
                LOCK_KEY_PREFIX + definition.redisKey(),
                definition.redisKey(),
                definition.prefix(),
                definition.width(),
                definition.maxSequence()
        );
        return nextSequence(sequenceDefinition, maxExistingSequenceSupplier, "编码号段已用尽");
    }
}