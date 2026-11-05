package com.qiheng.erp.ai.conversation;

import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/** 会话操作共用锁；保留原锁标识，避免清理前后的删除操作失去互斥。 */
@Service
@RequiredArgsConstructor
public class ConversationExecutionLock {

    private final RedissonClient redis;

    /** 获取指定会话的锁，租期由 Redisson watchdog 自动续期。 */
    public RLock of(long conversationId) {
        return redis.getLock("erp:ai:conversation:execution:" + conversationId);
    }
}
