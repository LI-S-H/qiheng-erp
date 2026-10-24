package com.qiheng.erp.ai.graph.support;

import com.qiheng.erp.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationThreadsTest {

    private final ConversationThreads threads = new ConversationThreads();

    @Test
    void threadIdRoundTripsForOwner() {
        String threadId = threads.of(101L, 205L);

        assertThat(threadId).isEqualTo("erp:ai:graph:v1:101:205");
        assertThat(threads.isOwned(threadId, 101L)).isTrue();
        assertThat(threads.parse(threadId, 101L)).isEqualTo(205L);
    }

    @Test
    void otherUserCannotParseThread() {
        String threadId = threads.of(101L, 205L);

        assertThat(threads.isOwned(threadId, 102L)).isFalse();
        assertThatThrownBy(() -> threads.parse(threadId, 102L)).isInstanceOf(BizException.class);
    }

    @Test
    void malformedThreadsAreRejected() {
        assertThat(threads.isOwned(null, 1L)).isFalse();
        assertThat(threads.isOwned("", 1L)).isFalse();
        assertThat(threads.isOwned("erp:ai:graph:v1:1:", 1L)).isFalse();
        assertThat(threads.isOwned("erp:ai:graph:v1:1:abc", 1L)).isFalse();
        assertThat(threads.isOwned("erp:ai:graph:v1:1", 1L)).isFalse();
        assertThat(threads.isOwned("erp:ai:graph:v1:10:5", 1L)).isFalse();
        // 前缀不正确一律按无权限拒绝，不泄漏其他用户是否存在该任务。
        assertThatThrownBy(() -> threads.parse("erp:ai:graph:v1:1:abc", 1L))
                .isInstanceOf(BizException.class).hasMessageContaining("无权限");
        // 前缀正确但超出 Long 范围才属于"任务标识无效"。
        assertThatThrownBy(() -> threads.parse("erp:ai:graph:v1:1:99999999999999999999", 1L))
                .isInstanceOf(BizException.class).hasMessageContaining("任务标识无效");
    }
}
