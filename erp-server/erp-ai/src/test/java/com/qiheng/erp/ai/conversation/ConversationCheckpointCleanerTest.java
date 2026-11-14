package com.qiheng.erp.ai.conversation;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 核实会话删除时的检查点清理。
 *
 * <p>框架没有删除接口，必须自己收集内容、反向索引和线程元信息，再用一段脚本一次删除；
 * Redis 抖动时要重试，最终失败也不能向上抛，否则用户删不掉会话。</p>
 */
class ConversationCheckpointCleanerTest {

    /** 主智能体与取数子图两个线程各自删除一次，且 key 覆盖内容、反向索引与元信息。 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void deletesContentReverseAndMetaForBothThreads() {
        var saver = mock(RedisSaver.class);
        var redis = mock(RedissonClient.class);
        var script = mock(RScript.class);
        when(redis.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        var first = mock(Checkpoint.class);
        when(first.getId()).thenReturn("cp-1");
        var second = mock(Checkpoint.class);
        when(second.getId()).thenReturn("cp-2");
        when(saver.list(any(RunnableConfig.class))).thenReturn(List.of(first, second));
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList()))
                .thenReturn(5L);

        new ConversationCheckpointCleaner(saver, redis).clearConversation(1L, 2L);

        var keys = ArgumentCaptor.forClass(List.class);
        verify(script, times(2)).eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                keys.capture());
        var mainKeys = (List<Object>) keys.getAllValues().get(0);
        assertThat(mainKeys).containsExactlyInAnyOrder(
                "graph:checkpoint:content:cp-1", "graph:thread:reverse:cp-1",
                "graph:checkpoint:content:cp-2", "graph:thread:reverse:cp-2",
                "graph:thread:meta:main:1:2");
        var sqlKeys = (List<Object>) keys.getAllValues().get(1);
        assertThat(sqlKeys).contains("graph:thread:meta:sql:1:2");
    }

    /** 脚本失败时每个线程重试三次后放弃，不把异常抛给调用方。 */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void retriesThreeTimesPerThreadThenGivesUp() {
        var saver = mock(RedisSaver.class);
        var redis = mock(RedissonClient.class);
        var script = mock(RScript.class);
        when(redis.getScript(any(org.redisson.client.codec.Codec.class))).thenReturn(script);
        when(saver.list(any(RunnableConfig.class))).thenReturn(List.of());
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList()))
                .thenThrow(new IllegalStateException("redis 不可用"));

        new ConversationCheckpointCleaner(saver, redis).clearConversation(1L, 2L);

        verify(script, times(6)).eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class),
                anyList());
    }
}