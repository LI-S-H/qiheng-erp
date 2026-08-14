package com.qiheng.erp.common.util;

/**
 * 主数据编码的格式定义。
 *
 * <p>业务模块只声明 Redis key、前缀和位宽；编码序列的并发控制与初始化由
 * {@link CodeNoGenerator} 统一处理。</p>
 *
 * @param redisKey Redis 序列 key
 * @param prefix 编码前缀
 * @param width 数字序号位宽
 */
public record CodeNoDefinition(String redisKey, String prefix, int width) {

    public CodeNoDefinition {
        if (redisKey == null || redisKey.isBlank()) {
            throw new IllegalArgumentException("Redis 序列 key 不能为空");
        }
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("编码前缀不能为空");
        }
        if (width < 1 || width > 18) {
            throw new IllegalArgumentException("编码序号位宽必须在 1 到 18 之间");
        }
    }

    /**
     * 当前位宽可容纳的最大数字序号。
     */
    public long maxSequence() {
        long limit = 1L;
        for (int index = 0; index < width; index++) {
            limit = Math.multiplyExact(limit, 10L);
        }
        return limit - 1L;
    }
}
