package com.qiheng.erp.ai.conversation;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 清理会话对应的图检查点。
 *
 * <p>框架的 {@link RedisSaver} 没有删除接口，{@code release} 只是把标记置为已释放，
 * key 仍然留在 Redis 里。这里按线程收集内容、反向索引与线程元信息，再用一段 Lua
 * 脚本一次删除，避免删到一半失败留下半截状态。</p>
 *
 * <p>Lua 多 key 在 Redis Cluster 上会因跨槽失败；当前部署为单机 Redis，
 * 上集群时需要改用 hash tag 或逐 key 删除。</p>
 *
 * @author Li
 * @since 2026-11-14
 */
@Component
public class ConversationCheckpointCleaner {
    private static final Logger log = LoggerFactory.getLogger(ConversationCheckpointCleaner.class);

    private static final String CONTENT_PREFIX = "graph:checkpoint:content:";
    private static final String REVERSE_PREFIX = "graph:thread:reverse:";
    private static final String META_PREFIX = "graph:thread:meta:";
    private static final String MAIN_THREAD_PREFIX = "main:";
    private static final String SQL_THREAD_PREFIX = "sql:";
    private static final int MAX_ATTEMPTS = 3;

    /** 一次性删除传入的全部 key，返回实际删除数量。 */
    private static final String DELETE_SCRIPT = """
            local removed = 0
            for index = 1, #KEYS do
                removed = removed + redis.call('DEL', KEYS[index])
            end
            return removed
            """;

    private final RedisSaver saver;
    private final RedissonClient redis;

    /**
     * 注入检查点存储与 Redis 客户端。
     *
     * @param saver 检查点存储
     * @param redis Redis 客户端
     */
    public ConversationCheckpointCleaner(RedisSaver saver, RedissonClient redis) {
        this.saver = saver;
        this.redis = redis;
    }

    /**
     * 清理某个会话的主智能体与取数子图检查点；失败只记录日志，不影响已完成的数据库删除。
     *
     * @param userId 会话所属用户
     * @param conversationId 会话编号
     */
    public void clearConversation(long userId, long conversationId) {
        clearThread(MAIN_THREAD_PREFIX + userId + ":" + conversationId);
        clearThread(SQL_THREAD_PREFIX + userId + ":" + conversationId);
    }

    /** 收集单个线程的全部 key 并原子删除。 */
    private void clearThread(String threadId) {
        Set<String> keys = new LinkedHashSet<>();
        collectCheckpointKeys(threadId, keys);
        keys.add(META_PREFIX + threadId);
        deleteWithRetry(threadId, keys);
    }

    /** 按线程收集检查点内容与反向索引；读取失败时仍保留线程元信息待删。 */
    private void collectCheckpointKeys(String threadId, Set<String> keys) {
        var config = RunnableConfig.builder().threadId(threadId).build();
        try {
            Collection<Checkpoint> checkpoints = saver.list(config);
            for (Checkpoint checkpoint : checkpoints) {
                if (checkpoint.getId() == null) {
                    continue;
                }
                keys.add(CONTENT_PREFIX + checkpoint.getId());
                keys.add(REVERSE_PREFIX + checkpoint.getId());
            }
        } catch (RuntimeException error) {
            log.warn("检查点列表读取失败，仅清理线程元信息 threadId={}", threadId, error);
        }
    }

    /** 用 Lua 一次删除全部 key；失败重试，最终失败只记录日志。 */
    private void deleteWithRetry(String threadId, Set<String> keys) {
        List<Object> arguments = new ArrayList<>(keys);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                Long removed = redis.getScript(StringCodec.INSTANCE).eval(
                        RScript.Mode.READ_WRITE, DELETE_SCRIPT, RScript.ReturnType.INTEGER, arguments);
                log.info("会话检查点已清理 threadId={} keys={} removed={}", threadId, keys.size(), removed);
                return;
            } catch (RuntimeException error) {
                if (attempt == MAX_ATTEMPTS) {
                    log.error("会话检查点清理失败 threadId={} keys={}", threadId, keys.size(), error);
                    return;
                }
                sleepBeforeRetry(attempt, threadId, error);
            }
        }
    }

    /** 重试前等待；中断时保留中断标记并放弃本次清理。 */
    private void sleepBeforeRetry(int attempt, String threadId, RuntimeException error) {
        try {
            Thread.sleep(200L * attempt);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("会话检查点清理被中断 threadId={}", threadId, error);
        }
    }
}