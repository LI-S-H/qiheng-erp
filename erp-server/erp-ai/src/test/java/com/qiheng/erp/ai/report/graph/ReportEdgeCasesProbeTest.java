package com.qiheng.erp.ai.report.graph;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 记录引用异常和材料不可用时当前图的实际路由行为。 */
class ReportEdgeCasesProbeTest {
    @Test
    void invalidReferenceStillFollowsReviewScore() throws Exception {
        var graph = new ReportGraph(
                state -> "销售增长 50%。[1]\n\n## 参考资料\n[2] 数据 ds_example",
                state -> new ReportGraph.Review(8, List.of("结构清楚"), List.of()));
        var output = graph.run(Map.of("userRequest", "分析销售", "analysisNarrative", "计算销售变化"));
        assertThat(output).containsEntry("status", "COMPLETED");
        assertThat(((Map<?, ?>) output.get("referenceValidation")).get("status")).isEqualTo("INVALID");
    }

    @Test
    void htmlReferenceIsExplicitlyUnsupportedByParser() {
        var validation = ReportReferences.extract("<p>库存数量为 5。[1]</p>\n<h2>参考资料</h2>\n[1] 库存 ds_demo");
        assertThat(validation).containsEntry("status", "UNSUPPORTED");
    }
}
