package com.qiheng.erp.ai.graph.support;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/** 执行与删除共用会话锁；不指定租期，由 Redisson watchdog 自动续期。 */
@Service
@RequiredArgsConstructor
public class ConversationExecutionLock {

    private final RedissonClient redis;

    public RLock of(long conversationId) {
        return redis.getLock("erp:ai:conversation:execution:" + conversationId);
    }
}
