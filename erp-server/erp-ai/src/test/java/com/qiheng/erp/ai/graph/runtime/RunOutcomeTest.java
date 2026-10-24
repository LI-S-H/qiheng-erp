package com.qiheng.erp.ai.graph.runtime;

import com.qiheng.erp.ai.graph.runtime.RunOutcome;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunOutcomeTest {

    @Test
    void waitingHumanSnapshotIsPausedNotEnded() {
        StateSnapshot snapshot = snapshot(GraphStateKeys.HUMAN_FEEDBACK_NODE,
                Map.of(GraphStateKeys.NEED_CHECK_RESULT, "WAITING_HUMAN"));

        RunOutcome outcome = RunOutcome.of(snapshot);

        assertThat(outcome.waitingHuman()).isTrue();
        assertThat(outcome.graphEnded()).isFalse();
        assertThat(outcome.status()).isEqualTo("WAITING_HUMAN");
    }

    @Test
    void readyPlanAtEndIsFinished() {
        StateSnapshot snapshot = snapshot(END, Map.of(GraphStateKeys.NEED_REVIEW_RESULT, "OK"));

        RunOutcome outcome = RunOutcome.of(snapshot);

        assertThat(outcome.graphEnded()).isTrue();
        assertThat(outcome.waitingHuman()).isFalse();
        assertThat(outcome.status()).isEqualTo("NEEDS_READY");
    }

    @Test
    void formatRetryExhaustedAtEndIsRecognized() {
        StateSnapshot snapshot = snapshot(END,
                Map.of(GraphStateKeys.NEED_CHECK_RESULT, "FORMAT_RETRY_EXHAUSTED"));

        assertThat(RunOutcome.of(snapshot).graphEnded()).isTrue();
        assertThat(RunOutcome.of(snapshot).status()).isEqualTo("FORMAT_RETRY_EXHAUSTED");
    }

    @Test
    void waitingHumanMarkerNotAtHumanNodeIsNotPaused() {
        // 暂停标记不在人工节点：缺少可继续的暂停点，不算本轮结局。
        StateSnapshot snapshot = snapshot(END, Map.of(GraphStateKeys.NEED_CHECK_RESULT, "WAITING_HUMAN"));

        RunOutcome outcome = RunOutcome.of(snapshot);

        assertThat(outcome.waitingHuman()).isFalse();
        assertThat(outcome.graphEnded()).isTrue();
        assertThat(outcome.status()).isNull();
    }

    @Test
    void readyResultInMiddleStateIsNotFinished() {
        // 到达 END 才允许宣称结局：图停在中间节点时状态为 null，读取路径据此降级。
        StateSnapshot snapshot = snapshot(GraphStateKeys.NEED_CHECK_NODE,
                Map.of(GraphStateKeys.NEED_REVIEW_RESULT, "OK"));

        RunOutcome outcome = RunOutcome.of(snapshot);

        assertThat(outcome.graphEnded()).isFalse();
        assertThat(outcome.status()).isNull();
    }

    @Test
    void endWithoutBusinessResultIsUnrecognized() {
        StateSnapshot snapshot = snapshot(END, Map.of(GraphStateKeys.NEED_CHECK_RESULT, ""));

        assertThat(RunOutcome.of(snapshot).status()).isNull();
    }

    private static StateSnapshot snapshot(String next, Map<String, Object> state) {
        StateSnapshot snapshot = mock(StateSnapshot.class);
        when(snapshot.next()).thenReturn(next);
        when(snapshot.state()).thenReturn(new OverAllState(state));
        return snapshot;
    }
}
