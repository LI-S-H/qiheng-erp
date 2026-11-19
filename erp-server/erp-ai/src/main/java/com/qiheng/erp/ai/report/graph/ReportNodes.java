package com.qiheng.erp.ai.report.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 报告节点使用的状态读取和具体执行逻辑。 */
final class ReportNodes {
    private ReportNodes() {}

    /** 读取本轮工作状态的副本，避免直接修改框架中的状态。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> work(OverAllState state) {
        return new LinkedHashMap<>((Map<String, Object>) state.value(ReportGraph.WORK).orElseThrow());
    }

    /** 兼容已有独立报告图调用的准备节点。 */
    static final class Prepare implements NodeAction {
        private final ReportGraph.Preparer preparer;

        Prepare(ReportGraph.Preparer preparer) {
            this.preparer = preparer;
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> apply(OverAllState state) throws Exception {
            Map<String, Object> work = work(state);
            Map<String, Object> input = (Map<String, Object>) work.get("input");
            for (String key : List.of("userRequest", "analysisNarrative")) {
                if (!(input.get(key) instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("报告任务缺少必要内容：" + key);
                }
            }
            Map<String, Object> prepared = new LinkedHashMap<>(input);
            prepared.putIfAbsent("confirmedConditions", "");
            prepared.putIfAbsent("datasetIds", List.of());
            prepared.putIfAbsent("resultIds", List.of());
            if (!(prepared.get("confirmedConditions") instanceof String)) {
                throw new IllegalArgumentException("报告确认条件必须是文字");
            }
            for (String key : List.of("datasetIds", "resultIds")) {
                if (!(prepared.get(key) instanceof List<?> ids)
                        || ids.stream().anyMatch(id -> !(id instanceof String text) || text.isBlank())) {
                    throw new IllegalArgumentException("报告材料编号必须是非空字符串列表：" + key);
                }
            }
            prepared.put("materialCatalog", preparer.prepare(Map.copyOf(prepared)));
            work.put("input", prepared);
            work.put("revisionCount", 0);
            return Map.of(ReportGraph.WORK, work);
        }
    }

    /** 撰写报告并在读取返修意见后清除上一轮审核。 */
    static final class Write implements NodeAction {
        private final ReportGraph.Writer writer;

        Write(ReportGraph.Writer writer) {
            this.writer = writer;
        }

        @Override
        public Map<String, Object> apply(OverAllState state) throws Exception {
            Map<String, Object> work = work(state);
            String content = writer.write(Map.copyOf(work));
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("报告内容为空");
            }
            work.put("content", content);
            work.put("referenceValidation", ReportReferences.extract(content));
            work.remove("review");
            work.remove("score");
            return Map.of(ReportGraph.WORK, work);
        }
    }

    /** 调用审核智能体并保存意见，评分路由由判断节点处理。 */
    static final class ReviewNode implements NodeAction {
        private final ReportGraph.Reviewer reviewer;

        ReviewNode(ReportGraph.Reviewer reviewer) {
            this.reviewer = reviewer;
        }

        @Override
        public Map<String, Object> apply(OverAllState state) throws Exception {
            Map<String, Object> work = work(state);
            ReportGraph.Review result = reviewer.review(Map.copyOf(work));
            if (result == null || !Double.isFinite(result.score()) || result.score() < 0 || result.score() > 10) {
                throw new IllegalStateException("报告审核评分无效");
            }
            if (result.strengths() == null || result.issues() == null) {
                throw new IllegalStateException("报告审核意见无效");
            }
            if (result.score() < 8 && result.issues().isEmpty()) {
                throw new IllegalStateException("审核未通过时必须说明问题");
            }
            work.put("score", result.score());
            work.put("review", Map.of("score", result.score(), "strengths", result.strengths(),
                    "issues", result.issues().stream().map(issue -> Map.of(
                            "location", issue.location(), "problem", issue.problem(),
                            "reason", issue.reason(), "suggestion", issue.suggestion())).toList()));
            return Map.of(ReportGraph.WORK, work);
        }
    }

    /** 根据审核分数和返修上限确定下一步。 */
    static final class Decide implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) {
            Map<String, Object> work = work(state);
            double score = ((Number) work.get("score")).doubleValue();
            int revisions = ((Number) work.get("revisionCount")).intValue();
            if (score >= 8) {
                work.put("status", "COMPLETED");
            } else if (revisions >= 3) {
                work.put("status", "REVIEW_FAILED");
            } else {
                work.put("revisionCount", revisions + 1);
                work.put("status", "REVISE");
            }
            return Map.of(ReportGraph.WORK, work);
        }
    }
}
