package com.qiheng.erp.ai.graph.runtime;

import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.graph.runtime.RunResumePreparer;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.domain.dto.GraphResumeRequest;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.graph.runtime.RunContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 恢复侧回答条目：只在检查点写回成功后补记，写回失败不落节点记忆。
 *
 * @author Li
 * @since 2026-10-19
 */
class RunResumePreparerTest {

    private static final String THREAD = "erp:ai:graph:v1:1:10";

    private final CompiledGraph graph = mock(CompiledGraph.class);
    private final AiConversationMapper conversations = mock(AiConversationMapper.class);
    private final ConversationMessageStore messageStore = mock(ConversationMessageStore.class);
    private final ConversationThreads threads = mock(ConversationThreads.class);
    private final NodeMemoryRecorder recorder = mock(NodeMemoryRecorder.class);

    @Test
    void answeredEntryIsWrittenOnlyAfterCheckpointUpdateSucceeds() throws Exception {
        stubResumePath();

        RunContext context = preparer().prepare(
                new GraphResumeRequest(THREAD, "pause-1", "  2026年9月  "), 1L, List.of("product:query"), "req-2");

        assertThat(context).isNotNull();
        // 回答条目携带恢复轮 requestId 与暂停点：读侧按 pauseId 与提问条目配对
        verify(recorder).clarificationAnswered(10L, 1L, "req-2", "pause-1", "2026年9月");
        // 会话消息只落用户回答行，不写助手消息
        verify(messageStore).append(anyLong(), anyLong(), eq("req-2"), any(), any(), any(), any(), any());
    }

    @Test
    void checkpointUpdateFailureDoesNotWriteAnsweredEntry() throws Exception {
        stubResumePath();
        when(graph.updateState(any(RunnableConfig.class), anyMap()))
                .thenThrow(new IllegalStateException("Redis 不可用"));

        RunResumePreparer preparer = preparer();
        assertThatThrownBy(() -> preparer.prepare(
                new GraphResumeRequest(THREAD, "pause-1", "2026年9月"), 1L, List.of("product:query"), "req-2"))
                .isInstanceOf(IllegalStateException.class);
        verify(recorder, never()).clarificationAnswered(anyLong(), anyLong(), any(), any(), any());
    }

    private void stubResumePath() throws Exception {
        when(threads.isOwned(THREAD, 1L)).thenReturn(true);
        when(threads.parse(THREAD, 1L)).thenReturn(10L);
        when(conversations.selectOwned(10L, 1L)).thenReturn(new AiConversation().setId(10L));
        StateSnapshot latest = waitingSnapshot();
        when(graph.stateOf(any())).thenReturn(Optional.of(latest));
        when(graph.updateState(any(RunnableConfig.class), anyMap())).thenReturn(threadConfig());
    }

    @SuppressWarnings("unchecked")
    private RunResumePreparer preparer() {
        ObjectProvider<CompiledGraph> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(graph);
        return new RunResumePreparer(provider, conversations, messageStore, threads, recorder);
    }

    private StateSnapshot waitingSnapshot() {
        StateSnapshot snapshot = mock(StateSnapshot.class);
        when(snapshot.next()).thenReturn(GraphStateKeys.HUMAN_FEEDBACK_NODE);
        when(snapshot.state()).thenReturn(new OverAllState(Map.of(
                GraphStateKeys.USER_ID, "1",
                GraphStateKeys.NEED_CHECK_RESULT, "WAITING_HUMAN",
                GraphStateKeys.PAUSE_ID, "pause-1")));
        when(snapshot.config()).thenReturn(threadConfig());
        return snapshot;
    }

    private RunnableConfig threadConfig() {
        return RunnableConfig.builder().threadId(THREAD).build();
    }
}
