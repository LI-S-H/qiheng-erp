package com.qiheng.erp.common.util;

/**
 * 序列号的格式定义。
 *
 * <p>子类声明分布式锁 key、Redis 序列 key、格式化前缀、位宽和最大序号；
 * 序列的并发控制与初始化由 {@link AbstractSequenceGenerator} 统一处理。</p>
 *
 * @param lockKey      分布式锁的完整 key
 * @param redisKey     Redis 序列 key
 * @param formatPrefix 格式化前缀（如 {@code RK20260815} 或 {@code WH}）
 * @param width        数字序号位宽
 * @param maxSequence  当前位宽可容纳的最大序号
 */
public record SequenceDefinition(String lockKey, String redisKey, String formatPrefix, int width, long maxSequence) {

    public SequenceDefinition {
        if (lockKey == null || lockKey.isBlank()) {
            throw new IllegalArgumentException("分布式锁 key 不能为空");
        }
        if (redisKey == null || redisKey.isBlank()) {
            throw new IllegalArgumentException("Redis 序列 key 不能为空");
        }
        if (formatPrefix == null || formatPrefix.isBlank()) {
            throw new IllegalArgumentException("格式化前缀不能为空");
        }
        if (width < 1 || width > 18) {
            throw new IllegalArgumentException("序号位宽必须在 1 到 18 之间");
        }
        if (maxSequence < 1) {
            throw new IllegalArgumentException("最大序号必须大于 0");
        }
    }
}