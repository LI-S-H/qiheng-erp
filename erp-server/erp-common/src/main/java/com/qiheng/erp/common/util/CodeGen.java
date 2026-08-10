package com.qiheng.erp.common.util;

import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 业务编码生成工具。
 *
 * <p>统一封装 Redis INCR + 前缀 + 补零格式化,供各业务模块新增时生成主键编码。</p>
 *
 * @author Li
 * @since 2026-08-10
 */
public final class CodeGen {

    private CodeGen() {
    }

    /**
     * 生成下一个业务编码（Redis 自增 + 前缀 + 补零）
     *
     * @param redis    Redis 模板
     * @param redisKey Redis 自增 key，如 {@code "customer:code"}
     * @param prefix   业务编码前缀，如 {@code "C"}
     * @param width    序号位数（不足补零），如 {@code 4} 表示 {@code 0001}
     * @return 业务编码
     */
    public static String next(StringRedisTemplate redis, String redisKey, String prefix, int width) {
        Long seq = redis.opsForValue().increment(redisKey);
        return prefix + String.format("%0" + width + "d", seq);
    }
}