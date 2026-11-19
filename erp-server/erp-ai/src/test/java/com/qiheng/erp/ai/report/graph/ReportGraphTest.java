package com.qiheng.erp.ai.report.graph;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportGraphTest {
    @Test
    void reviewerReceivesReferenceStructureWithoutChangingScoreRouting() throws Exception {
        var graph = new ReportGraph(state -> "库存为 10。[1]\n## 参考资料\n[2] 记录 ds_abc", state -> {
            assertThat(((Map<?, ?>) state.get("referenceValidation")).get("status")).isEqualTo("INVALID");
            return new ReportGraph.Review(8, List.of(), List.of());
        });
        assertThat(graph.run(Map.of("userRequest", "分析库存", "analysisNarrative", "查询库存")))
                .containsEntry("status", "COMPLETED");
    }

    @Test
    void revisesAtMostThreeTimesAndPassesFeedbackToWriter() throws Exception {
        var writes = new AtomicInteger();
        var reviews = new AtomicInteger();
        var graph = new ReportGraph(state -> {
            int number = writes.incrementAndGet();
            if (number > 1) {
                assertThat(state.get("review").toString()).contains("核对数字");
            }
            return "报告 " + number;
        }, state -> {
            reviews.incrementAndGet();
            return new ReportGraph.Review(7, List.of(), List.of(new ReportGraph.Issue("正文", "错误", "不一致", "核对数字")));
        });
        var result = graph.run(Map.of("userRequest", "分析库存", "analysisNarrative", "查询库存"));
        assertThat(result).containsEntry("status", "REVIEW_FAILED")
                .containsEntry("revisionCount", 3).containsEntry("content", "报告 4");
        assertThat(writes).hasValue(4);
        assertThat(reviews).hasValue(4);
    }

    @Test
    void passesAfterOneRevision() throws Exception {
        var calls = new AtomicInteger();
        var graph = new ReportGraph(state -> "报告", state ->
                new ReportGraph.Review(calls.getAndIncrement() == 0 ? 7 : 8, List.of(),
                        List.of(new ReportGraph.Issue("正文", "错误", "不一致", "核对数字"))));
        assertThat(graph.run(Map.of("userRequest", "分析", "analysisNarrative", "查询")))
                .containsEntry("status", "COMPLETED").containsEntry("revisionCount", 1);
    }

    @Test
    void rejectsInvalidScoreRatherThanPassing() throws Exception {
        var graph = new ReportGraph(state -> "报告", state -> new ReportGraph.Review(Double.NaN, List.of(), List.of()));
        assertThatThrownBy(() -> graph.run(Map.of("userRequest", "分析", "analysisNarrative", "查询")))
                .hasMessageContaining("报告审核评分无效");
    }
}
