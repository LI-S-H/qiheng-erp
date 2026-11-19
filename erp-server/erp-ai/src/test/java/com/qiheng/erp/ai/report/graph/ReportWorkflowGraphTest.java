package com.qiheng.erp.ai.report.graph;

import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.qiheng.erp.ai.report.ReportReview;
import com.qiheng.erp.ai.report.ReportProgress;
import com.qiheng.erp.ai.report.ReportReviewIssue;
import com.qiheng.erp.ai.report.ReportWriterOutputConverter;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;

/** 在真实图路由中验证报告阶段和状态切换。 */
class ReportWorkflowGraphTest {
    private final ReportReviewIssue issue = new ReportReviewIssue("引用错误", "编号不一致", "核对编号");

    private ReportGraph graph(NodeAction prepare, ReportWorkflowNodes.Writer writer,
                              ReportWorkflowNodes.Reviewer reviewer) throws Exception {
        return new ReportGraph(prepare, new ReportWorkflowNodes.PrepareDecide(),
                new ReportWorkflowNodes.Write(writer), new ReportWorkflowNodes.WriteDecide(),
                new ReportWorkflowNodes.Review(reviewer), new ReportWorkflowNodes.ReviewDecide());
    }

    private NodeAction ready() {
        return state -> {
            var work = ReportNodes.work(state);
            work.put("reportInput", "用户需求");
            work.put("revisionCount", 0);
            return Map.of(ReportGraph.WORK, work);
        };
    }

    @Test
    void unavailableAtPrepareDoesNotCallExperts() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NodeAction prepare = state -> {
            var work = ReportNodes.work(state);
            work.put("REFERENCE_UNAVAILABLE", Map.of("message", "数据集不可用"));
            return Map.of(ReportGraph.WORK, work);
        };
        var graph = graph(prepare, (input, feedback) -> {
            calls.incrementAndGet();
            return new ReportWriterOutputConverter.Completed("REPORT", "不应调用");
        }, (input, content, feedback) -> {
            calls.incrementAndGet();
            return new ReportReview(9.0, List.of(), false, List.of());
        });
        var output = graph.run(Map.of());
        assertThat(output).containsEntry("status", "FAILED").containsKey("REFERENCE_UNAVAILABLE");
        assertThat(calls).hasValue(0);
    }

    @Test
    void completedReportPasses() throws Exception {
        var graph = graph(ready(),
                (input, feedback) -> new ReportWriterOutputConverter.Completed("REPORT", "库存标题", "报告正文"),
                (input, content, feedback) -> {
                    assertThat(content).contains("库存标题", "报告正文");
                    return new ReportReview(8.0, List.of("清晰"), false, List.of());
                });
        assertThat(graph.run(Map.of())).containsEntry("status", "COMPLETED")
                .containsEntry("title", "库存标题").containsEntry("content", "报告正文").doesNotContainKey("writeResult");
    }

    @Test
    void unavailableWhileRevisingCannotDeliverOldReport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var graph = graph(ready(), (input, feedback) -> {
            if (calls.getAndIncrement() == 0) {
                return new ReportWriterOutputConverter.Completed("REPORT", "旧报告");
            }
            assertThat(feedback).contains("引用错误");
            return new ReportWriterOutputConverter.Unavailable("REFERENCE_UNAVAILABLE", "资料不可读",
                    List.of(new ReportWriterOutputConverter.Reference("dataset", "ds_1")));
        }, (input, content, feedback) -> new ReportReview(6.0, List.of(), true, List.of(issue)));
        var result = graph.run(Map.of());
        assertThat(result).containsEntry("status", "FAILED").containsKey("REFERENCE_UNAVAILABLE")
                .doesNotContainKeys("content", "review", "score", "writeResult");
        assertThat(calls).hasValue(2);
    }

    @Test
    void lowScoreRevisesAndReviewsNewContent() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger reviews = new AtomicInteger();
        var graph = graph(ready(), (input, feedback) -> {
            if (writes.incrementAndGet() == 1) {
                assertThat(feedback).isNull();
                return new ReportWriterOutputConverter.Completed("REPORT", "第一版");
            }
            assertThat(feedback).contains("引用错误", "编号不一致", "核对编号", "结构清晰");
            return new ReportWriterOutputConverter.Completed("REPORT", "第二版");
        }, (input, content, feedback) -> {
            if (reviews.incrementAndGet() == 1) {
                assertThat(content).contains("第一版");
                return new ReportReview(6.0, List.of("结构清晰"), true, List.of(issue));
            }
            assertThat(content).contains("第二版");
            assertThat(feedback).isNull();
            return new ReportReview(9.0, List.of(), false, List.of());
        });
        var result = graph.run(Map.of());
        assertThat(result).containsEntry("status", "COMPLETED").containsEntry("content", "第二版")
                .containsEntry("revisionCount", 1).containsEntry("score", 9.0)
                .doesNotContainKeys("writeResult", "writerFeedback", "reviewFeedback");
        assertThat(writes).hasValue(2);
        assertThat(reviews).hasValue(2);
    }

    @Test
    void revisionProgressFollowsActualWriterAndReviewerCalls() throws Exception {
        var events = new ArrayList<String>();
        var progress = new ReportProgress("call", (name, data) -> events.add(data.get("phase") + ":" + data.get("text")));
        var reviews = new AtomicInteger();
        var graph = new ReportGraph(ready(), new ReportWorkflowNodes.PrepareDecide(),
                new ReportWorkflowNodes.Write((input, feedback) ->
                        new ReportWriterOutputConverter.Completed("REPORT", feedback == null ? "第一版" : "第二版"), progress),
                new ReportWorkflowNodes.WriteDecide(),
                new ReportWorkflowNodes.Review((input, content, feedback) ->
                        reviews.getAndIncrement() == 0
                                ? new ReportReview(6.0, List.of(), true, List.of(issue))
                                : new ReportReview(9.0, List.of(), false, List.of()), progress),
                new ReportWorkflowNodes.ReviewDecide(progress));
        assertThat(graph.run(Map.of())).containsEntry("status", "COMPLETED");
        assertThat(events).containsExactly(
                "WRITING:根据已整理的资料编写报告。",
                "REVIEWING:报告编写完成，开始审阅报告。",
                "REVISING:审阅发现报告需要修改：引用错误",
                "WRITING:正在根据审阅意见修改报告。",
                "REVIEWING:报告修改完成，开始重新审阅。");
    }

    @Test
    void writeFailureDuringRevisionDoesNotKeepPreviousReview() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var graph = graph(ready(), (input, feedback) -> {
            if (calls.getAndIncrement() == 0) {
                return new ReportWriterOutputConverter.Completed("REPORT", "旧报告");
            }
            throw new IllegalStateException("模型暂时不可用");
        }, (input, content, feedback) -> new ReportReview(6.0, List.of(), true, List.of(issue)));
        var result = graph.run(Map.of());
        assertThat(result).containsEntry("status", "FAILED").containsKey("WRITE_FAILED")
                .doesNotContainKeys("content", "review", "score", "writeResult", "writerFeedback");
        assertThat(result).containsEntry("revisionCount", 1);
    }

    @Test
    void contradictionRereviewsWithoutRewriting() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger reviews = new AtomicInteger();
        var graph = graph(ready(), (input, feedback) -> {
            writes.incrementAndGet();
            return new ReportWriterOutputConverter.Completed("REPORT", "报告");
        }, (input, content, feedback) -> {
            if (reviews.incrementAndGet() == 1) {
                return new ReportReview(9.0, List.of(), true, List.of(issue));
            }
            assertThat(feedback).contains("hasReferenceIssue", "score");
            return new ReportReview(8.0, List.of(), false, List.of());
        });
        assertThat(graph.run(Map.of())).containsEntry("status", "COMPLETED")
                .containsEntry("consistencyRetryCount", 1).containsEntry("revisionCount", 0);
        assertThat(writes).hasValue(1);
        assertThat(reviews).hasValue(2);
    }

    @Test
    void contradictionAndRevisionBudgetsStopWithoutOldReport() throws Exception {
        AtomicInteger reviews = new AtomicInteger();
        var inconsistent = graph(ready(),
                (input, feedback) -> new ReportWriterOutputConverter.Completed("REPORT", "报告"),
                (input, content, feedback) -> {
                    reviews.incrementAndGet();
                    return new ReportReview(9.0, List.of(), true, List.of(issue));
                });
        assertThat(inconsistent.run(Map.of())).containsEntry("status", "FAILED")
                .containsKey("REVIEW_INCONSISTENT").doesNotContainKey("content");
        assertThat(reviews).hasValue(4);

        AtomicInteger writes = new AtomicInteger();
        var lowScore = graph(ready(), (input, feedback) -> {
            writes.incrementAndGet();
            return new ReportWriterOutputConverter.Completed("REPORT", "报告");
        }, (input, content, feedback) -> new ReportReview(6.0, List.of(), true, List.of(issue)));
        assertThat(lowScore.run(Map.of())).containsEntry("status", "FAILED")
                .containsEntry("revisionCount", 3).containsKey("REVIEW_FAILED").doesNotContainKey("content");
        assertThat(writes).hasValue(4);
    }
}
