package com.qiheng.erp.ai.graph.runtime;

import com.alibaba.cloud.ai.graph.state.StateSnapshot;
import com.qiheng.erp.ai.constant.GraphStateKeys;
import com.qiheng.erp.ai.constant.NeedCheckResult;
import com.qiheng.erp.ai.constant.NeedReviewResult;
import com.qiheng.erp.ai.constant.RunStatuses;

import static com.alibaba.cloud.ai.graph.StateGraph.END;

/**
 * 一轮图执行的结局：从检查点快照读出的"图走到哪里、结果是什么"。
 *
 * <p>正常结束的唯一判据是图到达 END（{@link #graphEnded()}）；NEEDS_READY 与
 * FORMAT_RETRY_EXHAUSTED 只是到达 END 后的载荷分类。读取路径用 {@link #status()}
 * 静默降级，收尾路径据此决定发布哪种终态事件，两处共用同一份判定。</p>
 *
 * @param snapshot 本轮结束后的检查点快照
 * @author Li
 * @since 2026-10-16
 */
public record RunOutcome(StateSnapshot snapshot) {

    /**
     * 从检查点快照构造运行结局。
     *
     * @param snapshot 检查点快照
     * @return 运行结局
     */
    public static RunOutcome of(StateSnapshot snapshot) {
        return new RunOutcome(snapshot);
    }

    /** 图是否正常执行到 END：只有到达 END 才是正常结束。 */
    public boolean graphEnded() {
        return END.equals(snapshot.next());
    }

    /** 是否暂停在人工节点等待补充。 */
    public boolean waitingHuman() {
        return GraphStateKeys.HUMAN_FEEDBACK_NODE.equals(snapshot.next())
                && NeedCheckResult.WAITING_HUMAN.name().equals(checkResult());
    }

    /**
     * 本轮结局的对外状态：等待补充、需求就绪或格式重试耗尽。
     *
     * <p>需求就绪与格式重试耗尽只在图到达 END 时给出；不处于可返回结局
     * （图还在中间态）时返回 null，读取路径据此静默降级，不因中间态抛错。</p>
     *
     * @return 对外状态；快照不在可返回结局时为 null
     */
    public String status() {
        if (waitingHuman()) {
            return RunStatuses.WAITING_HUMAN;
        }
        if (!graphEnded()) {
            return null;
        }
        if (NeedReviewResult.OK.name().equals(reviewResult())) {
            return RunStatuses.NEEDS_READY;
        }
        if (NeedCheckResult.FORMAT_RETRY_EXHAUSTED.name().equals(checkResult())
                || NeedReviewResult.FORMAT_RETRY_EXHAUSTED.name().equals(reviewResult())) {
            return RunStatuses.FORMAT_RETRY_EXHAUSTED;
        }
        return null;
    }

    private String checkResult() {
        return snapshot.state().value(GraphStateKeys.NEED_CHECK_RESULT, "");
    }

    private String reviewResult() {
        return snapshot.state().value(GraphStateKeys.NEED_REVIEW_RESULT, "");
    }
}
