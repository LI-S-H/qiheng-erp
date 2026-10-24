package com.qiheng.erp.ai.graph.runtime;

import com.qiheng.erp.ai.graph.runtime.RunProgressTracker;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.domain.vo.RunArtifactEventVO;
import com.qiheng.erp.ai.domain.vo.RunFinishedEventVO;
import com.qiheng.erp.ai.domain.vo.RunStoppedEventVO;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 产物事件防重簿记单测：同一 Run 的重放快照只转发一次产物，检查点缺失时载荷可空。
 *
 * <p>发布拍写入 State 的形状由 PlanCheckNodeTest 覆盖，这里只验证追踪器的转发与防重。</p>
 *
 * @author Li
 * @since 2026-10-17
 */
class RunProgressTrackerTest {

    private final RunProgressPublisher publisher = mock(RunProgressPublisher.class);
    private final RunProgressPublisher.Channel channel = mock(RunProgressPublisher.Channel.class);
    private final RunProgressTracker tracker = RunProgressTracker.of(publisher, 1L, channel);

    /** 构造一帧快照：artifact 为 null 表示没有产物投影，checkpointId 为 null 表示快照未携带检查点。 */
    private StateSnapshot snapshot(Map<String, Object> artifact, String checkpointId) {
        return snapshot(artifact, checkpointId, null);
    }

    /** 追加本轮请求标识：产物归属过滤用 State 的 REQUEST_ID 对照投影自带 requestId。 */
    private StateSnapshot snapshot(Map<String, Object> artifact, String checkpointId, String requestId) {
        Map<String, Object> state = new HashMap<>();
        if (artifact != null) {
            state.put(GraphStateKeys.PUBLISHED_ARTIFACT, artifact);
        }
        if (requestId != null) {
            state.put(GraphStateKeys.REQUEST_ID, requestId);
        }
        StateSnapshot result = mock(StateSnapshot.class);
        when(result.state()).thenReturn(new OverAllState(state));
        when(result.config()).thenReturn(buildConfig(checkpointId));
        when(result.node()).thenReturn(GraphStateKeys.NEED_CHECK_NODE);
        return result;
    }

    /** 快照层检查点标识只在 StateSnapshot 可得，人工暂停重放时可能缺失。 */
    private RunnableConfig buildConfig(String checkpointId) {
        if (checkpointId == null) {
            return RunnableConfig.builder().threadId("thread-1").build();
        }
        return RunnableConfig.builder().threadId("thread-1").checkPointId(checkpointId).build();
    }

    /** PLAN 产物投影，与发布拍写入形状一致。 */
    private Map<String, Object> planArtifact() {
        Map<String, Object> content = new HashMap<>();
        content.put("type", "PLAN");
        content.put("data", new HashMap<>());
        Map<String, Object> artifact = new HashMap<>();
        artifact.put("type", "PLAN");
        artifact.put("version", "1");
        artifact.put("content", content);
        return artifact;
    }

    /** 澄清产物投影：只写入发布拍，落库与 checkpointId 归属留给收尾。 */
    private Map<String, Object> clarificationArtifact() {
        Map<String, Object> data = new HashMap<>();
        data.put("text", "需要补充");
        Map<String, Object> content = new HashMap<>();
        content.put("type", "CLARIFICATION");
        content.put("data", data);
        Map<String, Object> artifact = new HashMap<>();
        artifact.put("type", "CLARIFICATION");
        artifact.put("version", "1");
        artifact.put("content", content);
        return artifact;
    }

    @Test
    void firstSnapshotPublishesArtifactWithCheckpointId() {
        tracker.onSnapshot(snapshot(planArtifact(), "cp-1"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(1)).publish(eq(1L), eq(channel),
                eq(RunProgressPublisher.EVENT_ARTIFACT), payload.capture());
        RunArtifactEventVO event = (RunArtifactEventVO) payload.getValue();
        assertThat(event.type()).isEqualTo("PLAN");
        assertThat(event.checkpointId()).isEqualTo("cp-1");
        assertThat(event.content()).isEqualTo(planArtifact().get("content"));
    }

    @Test
    void replayedSnapshotDoesNotRepublishArtifact() {
        tracker.onSnapshot(snapshot(planArtifact(), "cp-1"));
        // 人工暂停会让同一产物随重放出现在后续快照，这里用新快照对象承载相同投影
        tracker.onSnapshot(snapshot(planArtifact(), "cp-1"));
        tracker.onSnapshot(snapshot(planArtifact(), "cp-1"));

        verify(publisher, times(1)).publish(eq(1L), eq(channel),
                eq(RunProgressPublisher.EVENT_ARTIFACT), any());
    }

    @Test
    void snapshotWithoutCheckpointPublishesNullCheckpointId() {
        tracker.onSnapshot(snapshot(planArtifact(), null));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(1)).publish(eq(1L), eq(channel),
                eq(RunProgressPublisher.EVENT_ARTIFACT), payload.capture());
        assertThat(((RunArtifactEventVO) payload.getValue()).checkpointId()).isNull();
    }

    @Test
    void versionChangeRepublishesArtifact() {
        tracker.onSnapshot(snapshot(planArtifact(), "cp-1"));

        Map<String, Object> updated = planArtifact();
        updated.put("version", "2");
        tracker.onSnapshot(snapshot(updated, "cp-1"));

        // 同一产物新版本的新快照视为新内容，允许再次推送
        verify(publisher, times(2)).publish(eq(1L), eq(channel),
                eq(RunProgressPublisher.EVENT_ARTIFACT), any());
    }

    @Test
    void framesWithoutArtifactPublishNothing() {
        tracker.onSnapshot(snapshot(null, "cp-1"));
        tracker.onSnapshot(snapshot(null, null));

        verify(publisher, times(0)).publish(eq(1L), eq(channel),
                eq(RunProgressPublisher.EVENT_ARTIFACT), any());
    }

    @Test
    void clarificationArtifactPublishesClarificationRequiredEvent() {
        // 澄清投影走独立事件名：前端据此转入待补充表单而不是普通产物渲染
        tracker.onSnapshot(snapshot(clarificationArtifact(), "cp-2"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(1)).publish(eq(1L), eq(channel),
                eq(RunProgressPublisher.EVENT_CLARIFICATION_REQUIRED), payload.capture());
        RunArtifactEventVO event = (RunArtifactEventVO) payload.getValue();
        assertThat(event.type()).isEqualTo("CLARIFICATION");
        assertThat(event.checkpointId()).isEqualTo("cp-2");
        assertThat(event.content()).isEqualTo(clarificationArtifact().get("content"));
        verify(publisher, times(0)).publish(eq(1L), eq(channel),
                eq(RunProgressPublisher.EVENT_ARTIFACT), any());
    }

    @Test
    void inheritedArtifactFromPreviousRequestIsNotPublished() {
        // 恢复轮首帧回放起点检查点：里面的投影属于上一轮请求，不转发也不占用防重指纹
        Map<String, Object> artifact = clarificationArtifact();
        artifact.put("requestId", "run-old");
        tracker.onSnapshot(snapshot(artifact, "cp-1", "run-new"));

        verify(publisher, times(0)).publish(eq(1L), eq(channel),
                eq(RunProgressPublisher.EVENT_CLARIFICATION_REQUIRED), any());
    }

    @Test
    void artifactOfCurrentRequestIsPublishedDespitePreviousReplay() {
        Map<String, Object> inherited = clarificationArtifact();
        inherited.put("requestId", "run-old");
        tracker.onSnapshot(snapshot(inherited, "cp-1", "run-new"));

        Map<String, Object> current = clarificationArtifact();
        current.put("requestId", "run-new");
        tracker.onSnapshot(snapshot(current, "cp-2", "run-new"));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(publisher, times(1)).publish(eq(1L), eq(channel),
                eq(RunProgressPublisher.EVENT_CLARIFICATION_REQUIRED), payload.capture());
        assertThat(((RunArtifactEventVO) payload.getValue()).checkpointId()).isEqualTo("cp-2");
    }

    @Test
    void finishedPublishesThinRunFinishedEvent() {
        tracker.finished("NEEDS_READY", "历史保存失败");

        verify(publisher, times(1)).terminal(channel, RunProgressPublisher.EVENT_RUN_FINISHED,
                new RunFinishedEventVO("NEEDS_READY", "历史保存失败"));
    }

    @Test
    void stoppedPublishesThinRunStoppedEvent() {
        tracker.stopped("FAILED", "本轮任务执行失败，请稍后重试。", null);

        verify(publisher, times(1)).terminal(channel, RunProgressPublisher.EVENT_RUN_STOPPED,
                new RunStoppedEventVO("FAILED", "本轮任务执行失败，请稍后重试。", null));
    }
}
