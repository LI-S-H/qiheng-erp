package com.qiheng.erp.ai.graph.runtime;

import com.qiheng.erp.ai.graph.support.ConversationExecutionLock;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.graph.runtime.RunCoordinator;
import com.qiheng.erp.ai.graph.runtime.RunStartPreparer;
import com.qiheng.erp.ai.graph.runtime.RunResumePreparer;
import com.qiheng.erp.ai.graph.runtime.RunFailureHandler;
import com.qiheng.erp.ai.graph.runtime.RunStateResolver;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.domain.dto.GraphStartRequest;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.domain.vo.RunStoppedEventVO;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.service.VisibleMessageCodec;
import com.qiheng.erp.ai.service.impl.GraphServiceImpl;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GraphExecutionBoundaryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AiConversationMapper conversations = mock(AiConversationMapper.class);
    private final AiMessageMapper messages = mock(AiMessageMapper.class);
    private final ConversationExecutionLock locks = mock(ConversationExecutionLock.class);
    private final RLock lock = mock(RLock.class);
    private final RunProgressPublisher publisher = new RunProgressPublisher();
    private final CompiledGraph graph = mock(CompiledGraph.class);

    @Test
    void busyRequestHasNoDatabaseSideEffectsAndKeepsOtherChannel() {
        var existing = publisher.create("existing");
        publisher.register(10, existing);
        when(lock.tryLock()).thenReturn(false);
        when(locks.of(10)).thenReturn(lock);
        when(conversations.selectOwned(10L, 1L)).thenReturn(new AiConversation());
        GraphServiceImpl service = service();
        try (var users = login()) {
            var events = service.stream(new GraphStartRequest(10L, "分析库存")).collectList().block();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst().event()).isEqualTo("run_stopped");
            RunStoppedEventVO stopped = MAPPER.convertValue(events.getFirst().data(), RunStoppedEventVO.class);
            assertThat(stopped.status()).isEqualTo("FAILED");
            assertThat(stopped.reason()).contains("正在执行");
            assertThat(stopped.warning()).isNull();
        }
        verifyNoInteractions(messages);
        verify(conversations, never()).touchLastMessageAt(anyLong(), any());
        verify(conversations, never()).updateTitle(anyLong(), anyString());
        assertThat(publisher.hasActiveRun(10)).isTrue();
        verify(lock, never()).unlock();
        publisher.close(10, existing);
    }

    @Test
    void streamGraphFailureFinishesUserRequestAndReleasesExecution() {
        when(lock.tryLock()).thenReturn(true);
        when(locks.of(10)).thenReturn(lock);
        when(conversations.selectOwned(10L, 1L))
                .thenReturn(new AiConversation().setId(10L).setTitle("库存分析"));
        when(conversations.touchLastMessageAt(eq(10L), any())).thenReturn(1);
        when(graph.streamSnapshots(any(), any())).thenReturn(Flux.error(new IllegalStateException("内部模型异常")));
        GraphServiceImpl service = service();
        try (var users = login()) {
            // 流式入口不抛异常给调用方，订阅过程中失败处理仍要跑通 finishRequest + 解锁 + 清 channel
            service.stream(new GraphStartRequest(10L, "分析库存")).blockLast();
        }
        verify(messages).finishRequest(eq(10L), eq(1L), anyString(), eq("FAILED"), eq("AI_RUN_FAILED"));
        verify(lock).unlock();
        assertThat(publisher.hasActiveRun(10)).isFalse();
    }

    @Test
    void staleCheckpointWithoutActiveExecutionDoesNotMeanRunning() {
        when(messages.selectLatestInput(10, 1)).thenReturn(new AiMessage()
                .setRequestId("request").setRunStatus("RUNNING"));
        when(graph.stateOf(any())).thenReturn(java.util.Optional.empty());
        assertThat(service().runState(10L, 1L).status()).isEqualTo("INTERRUPTED");
    }

    @Test
    void redisFailureDuringDetailReadFallsBackToSavedNeeds() {
        when(messages.selectLatestInput(10, 1)).thenReturn(new AiMessage()
                .setRequestId("completed").setRunStatus("COMPLETED"));
        when(graph.stateOf(any())).thenThrow(new IllegalStateException("Redis 不可用"));
        when(messages.selectFinalByRequest(10, 1, "completed")).thenReturn(new AiMessage()
                .setContent("{\"type\":\"NEEDS\",\"data\":{\"text\":\"已拆解需求\"}}"));
        assertThat(service().runState(10L, 1L).status()).isEqualTo("NEEDS_READY");
    }

    @Test
    void redisFailureDoesNotClaimOldQuestionCanResume() {
        when(messages.selectLatestInput(10, 1)).thenReturn(new AiMessage()
                .setRequestId("paused").setRunStatus("COMPLETED"));
        when(graph.stateOf(any())).thenThrow(new IllegalStateException("Redis 不可用"));
        when(messages.selectFinalByRequest(10, 1, "paused")).thenReturn(new AiMessage()
                .setContent("{\"type\":\"CLARIFICATION\",\"data\":{\"text\":\"需要补充\"}}"));
        assertThat(service().runState(10L, 1L).status()).isEqualTo("INTERRUPTED");
    }

    @Test
    void legacyPlainAssistantReplyIsNotACompletedPlan() {
        when(messages.selectLatestInput(10, 1)).thenReturn(new AiMessage()
                .setRequestId("legacy").setRunStatus("COMPLETED"));
        when(graph.stateOf(any())).thenReturn(java.util.Optional.empty());
        when(messages.selectFinalByRequest(10, 1, "legacy")).thenReturn(new AiMessage()
                .setContent("{\"text\":\"历史普通回复\"}"));
        assertThat(service().runState(10L, 1L).status()).isEqualTo("IDLE");
    }

    @Test
    void legacyAssistantFailureStatusRemainsFailure() {
        when(messages.selectLatestInput(10, 1)).thenReturn(new AiMessage()
                .setRequestId("legacy").setRunStatus("RUNNING"));
        when(graph.stateOf(any())).thenReturn(java.util.Optional.empty());
        when(messages.selectFinalByRequest(10, 1, "legacy")).thenReturn(new AiMessage()
                .setRunStatus("FAILED").setContent("{\"text\":\"历史失败提示\"}"));
        assertThat(service().runState(10L, 1L).status()).isEqualTo("FAILED");
    }

    @Test
    void newStartCannotOverwritePendingHumanQuestion() {
        when(lock.tryLock()).thenReturn(true);
        when(locks.of(10)).thenReturn(lock);
        when(conversations.selectOwned(10L, 1L))
                .thenReturn(new AiConversation().setId(10L).setTitle("库存分析"));
        StateSnapshot pause = mock(StateSnapshot.class);
        when(pause.next()).thenReturn(com.qiheng.erp.ai.constant.GraphStateKeys.HUMAN_FEEDBACK_NODE);
        when(pause.state()).thenReturn(new OverAllState(Map.of(
                com.qiheng.erp.ai.constant.GraphStateKeys.NEED_CHECK_RESULT, "WAITING_HUMAN")));
        when(graph.stateOf(any())).thenReturn(java.util.Optional.of(pause));
        try (var users = login()) {
            ServerSentEvent<Object> stoppedEvent = service().stream(new GraphStartRequest(10L, "新需求"))
                    .filter(sse -> "run_stopped".equals(sse.event()))
                    .blockLast();
            assertThat(stoppedEvent).isNotNull();
            RunStoppedEventVO stopped = MAPPER.convertValue(stoppedEvent.data(), RunStoppedEventVO.class);
            assertThat(stopped.reason()).contains("先回答当前问题");
        }
        verify(messages, never()).insert(any(AiMessage.class));
        verify(conversations, never()).touchLastMessageAt(anyLong(), any());
        verify(graph, never()).stream(any(), any());
        verify(lock).unlock();
    }

    @Test
    void formatExhaustedEndingSettlesInputRowAndStopsWithoutFailurePath() {
        when(lock.tryLock()).thenReturn(true);
        when(locks.of(10)).thenReturn(lock);
        when(conversations.selectOwned(10L, 1L))
                .thenReturn(new AiConversation().setId(10L).setTitle("库存分析"));
        when(conversations.touchLastMessageAt(eq(10L), any())).thenReturn(1);
        when(graph.stateOf(any())).thenReturn(java.util.Optional.empty());
        when(graph.streamSnapshots(any(), any())).thenReturn(Flux.empty());
        StateSnapshot ending = snapshotOf(END, "FORMAT_RETRY_EXHAUSTED");
        when(graph.getState(any())).thenReturn(ending);
        try (var users = login()) {
            var frames = service().stream(new GraphStartRequest(10L, "分析库存")).collectList().block();
            // 收尾不再补写消息：消息归发布拍节点，这里只有一行用户输入行结清
            verify(messages).finishRequest(eq(10L), eq(1L), anyString(), eq("COMPLETED"), isNull());
            RunStoppedEventVO stopped = stoppedEvent(frames);
            assertThat(stopped.status()).isEqualTo("FORMAT_RETRY_EXHAUSTED");
            assertThat(stopped.reason()).isNull();
            assertThat(stopped.warning()).isNull();
        }
        verify(messages, never()).finishRequest(anyLong(), anyLong(), anyString(), eq("FAILED"), anyString());
        verify(lock).unlock();
    }

    @Test
    void settleFailureDoesNotTurnSuccessfulEndingIntoFailure() {
        when(lock.tryLock()).thenReturn(true);
        when(locks.of(10)).thenReturn(lock);
        when(conversations.selectOwned(10L, 1L))
                .thenReturn(new AiConversation().setId(10L).setTitle("库存分析"));
        when(conversations.touchLastMessageAt(eq(10L), any())).thenReturn(1);
        when(graph.stateOf(any())).thenReturn(java.util.Optional.empty());
        when(graph.streamSnapshots(any(), any())).thenReturn(Flux.empty());
        StateSnapshot ending = snapshotOf(END, "FORMAT_RETRY_EXHAUSTED");
        when(graph.getState(any())).thenReturn(ending);
        doThrow(new IllegalStateException("展示库暂时不可用")).when(messages)
                .finishRequest(eq(10L), eq(1L), anyString(), eq("COMPLETED"), isNull());
        try (var users = login()) {
            var frames = service().stream(new GraphStartRequest(10L, "分析库存")).collectList().block();
            // 结清失败只记日志：终态仍按图结局发布，不误标为失败
            RunStoppedEventVO stopped = stoppedEvent(frames);
            assertThat(stopped.status()).isEqualTo("FORMAT_RETRY_EXHAUSTED");
            assertThat(stopped.warning()).isNull();
        }
        verify(messages, never()).finishRequest(anyLong(), anyLong(), anyString(), eq("FAILED"), anyString());
        verify(lock).unlock();
    }

    @Test
    void middleStateWithoutEndOrPauseFailsInsteadOfClosingSilently() {
        when(lock.tryLock()).thenReturn(true);
        when(locks.of(10)).thenReturn(lock);
        when(conversations.selectOwned(10L, 1L))
                .thenReturn(new AiConversation().setId(10L).setTitle("库存分析"));
        when(conversations.touchLastMessageAt(eq(10L), any())).thenReturn(1);
        when(graph.stateOf(any())).thenReturn(java.util.Optional.empty());
        when(graph.streamSnapshots(any(), any())).thenReturn(Flux.empty());
        StateSnapshot middle = snapshotOf(GraphStateKeys.NEED_CHECK_NODE, "");
        when(graph.getState(any())).thenReturn(middle);
        try (var users = login()) {
            var frames = service().stream(new GraphStartRequest(10L, "分析库存")).collectList().block();
            // 中间态不是正常收口：走失败路径，用户输入行标记失败并补错误提示
            // 落库两次：用户输入行 + 失败提示卡
            verify(messages, times(2)).insert(any(AiMessage.class));
            verify(messages).finishRequest(eq(10L), eq(1L), anyString(), eq("FAILED"), eq("AI_RUN_FAILED"));
            RunStoppedEventVO stopped = stoppedEvent(frames);
            assertThat(stopped.status()).isEqualTo("FAILED");
            assertThat(stopped.reason()).isEqualTo(RunFailureHandler.FAILED_MESSAGE_TEXT);
        }
        verify(lock).unlock();
    }

    @SuppressWarnings("unchecked")
    private GraphServiceImpl service() {
        ObjectProvider<CompiledGraph> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(graph);
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ObjectMapper mapper = new ObjectMapper();
        TransactionTemplate transactions = new TransactionTemplate(tx);
        VisibleMessageCodec codec = new VisibleMessageCodec(mapper);
        ConversationMessageStore store = new ConversationMessageStore(messages, conversations, codec, transactions);
        ConversationThreads threads = new ConversationThreads();
        RunStartPreparer startPreparer =
                new RunStartPreparer(provider, conversations, store, threads, codec);
        RunResumePreparer resumePreparer = new RunResumePreparer(provider, conversations, store, threads,
                mock(NodeMemoryRecorder.class));
        RunFailureHandler failureHandler = new RunFailureHandler(store);
        RunCoordinator coordinator = new RunCoordinator(locks, publisher, conversations, threads,
                startPreparer, resumePreparer, failureHandler, store);
        RunStateResolver stateResolver = new RunStateResolver(publisher, store, provider, codec, threads);
        return new GraphServiceImpl(provider, coordinator, stateResolver);
    }

    private static StateSnapshot snapshotOf(String next, String checkResult) {
        StateSnapshot snapshot = mock(StateSnapshot.class);
        when(snapshot.next()).thenReturn(next);
        when(snapshot.state()).thenReturn(new OverAllState(Map.of(
                GraphStateKeys.NEED_CHECK_RESULT, checkResult)));
        return snapshot;
    }

    private static RunStoppedEventVO stoppedEvent(List<ServerSentEvent<Object>> frames) {
        ServerSentEvent<Object> stopped = frames.stream()
                .filter(sse -> "run_stopped".equals(sse.event()))
                .findFirst()
                .orElseThrow();
        return MAPPER.convertValue(stopped.data(), RunStoppedEventVO.class);
    }

    private org.mockito.MockedStatic<UserContext> login() {
        LoginUser user = new LoginUser();
        user.setUserId(1L);
        var users = mockStatic(UserContext.class);
        users.when(UserContext::requireCurrentUser).thenReturn(user);
        return users;
    }
}
