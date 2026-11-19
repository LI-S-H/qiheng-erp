package com.qiheng.erp.ai.report.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.qiheng.erp.ai.report.ReportReview;
import com.qiheng.erp.ai.report.ReportProgress;
import com.qiheng.erp.ai.report.ReportReviewIssue;
import com.qiheng.erp.ai.report.ReportReviewValidationHook;
import com.qiheng.erp.ai.report.ReportWriterOutputConverter;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 报告各阶段的工作和路由，使用本次任务的独立状态。 */
final class ReportWorkflowNodes {
    private static final Logger log = LoggerFactory.getLogger(ReportWorkflowNodes.class);
    private static final int MAX_REVISIONS = 3;
    private static final int MAX_CONSISTENCY_RETRIES = 3;

    private ReportWorkflowNodes() {}

    @FunctionalInterface
    interface Writer {
        ReportWriterOutputConverter.Output write(String reportInput, String feedback) throws Exception;
    }

    @FunctionalInterface
    interface Reviewer {
        ReportReview review(String reportInput, String content, String feedback) throws Exception;
    }

    /** 准备节点出错时结束，成功后启动报告撰写。 */
    static final class PrepareDecide implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) {
            var work = ReportNodes.work(state);
            if (work.containsKey("REFERENCE_UNAVAILABLE") || work.containsKey("PREPARE_FAILED")) {
                work.put("status", "FAILED");
                work.put("route", "END");
            } else if (work.get("reportInput") instanceof String input && !input.isBlank()) {
                work.put("route", "WRITE");
            } else {
                fail(work, "PREPARE_FAILED", "报告任务准备失败，请稍后重试。");
            }
            return Map.of(ReportGraph.WORK, work);
        }
    }

    /** 调用报告专家；仅在消耗返修意见后清理过期的阶段结果。 */
    static final class Write implements NodeAction {
        private final Writer writer;
        private final ReportProgress progress;

        Write(Writer writer) {
            this(writer, null);
        }

        Write(Writer writer, ReportProgress progress) {
            this.writer = writer;
            this.progress = progress;
        }

        @Override
        public Map<String, Object> apply(OverAllState state) {
            var work = ReportNodes.work(state);
            String feedback = (String) work.remove("writerFeedback");
            if (progress != null) {
                progress.stage("WRITING", feedback == null
                        ? "根据已整理的资料编写报告。"
                        : "正在根据审阅意见修改报告。");
            }
            work.remove("writeResult");
            work.remove("route");
            work.remove("REFERENCE_UNAVAILABLE");
            work.remove("WRITE_FAILED");
            work.remove("REPORT_FORMAT_FAILED");
            try {
                var result = writer.write((String) work.get("reportInput"), feedback);
                if (result == null) {
                    throw new IllegalStateException("报告撰写结果为空");
                }
                work.put("writeResult", result);
            } catch (Exception error) {
                log.warn("报告撰写失败 errorType={}", error.getClass().getSimpleName());
                if (causedBy(error, ReportWriterOutputConverter.InvalidOutput.class)) {
                    fail(work, "REPORT_FORMAT_FAILED", "报告输出格式多次修正后仍然无效。");
                } else {
                    fail(work, "WRITE_FAILED", "报告撰写失败，请稍后重试。");
                }
            }
            return Map.of(ReportGraph.WORK, work);
        }
    }

    /** 只根据本次撰写结果决定进入审阅或结束。 */
    static final class WriteDecide implements NodeAction {
        @Override
        public Map<String, Object> apply(OverAllState state) {
            var work = ReportNodes.work(state);
            if (work.containsKey("WRITE_FAILED") || work.containsKey("REPORT_FORMAT_FAILED")) {
                work.put("route", "END");
            } else if (work.get("writeResult") instanceof ReportWriterOutputConverter.Completed completed) {
                work.put("title", completed.title());
                work.put("content", completed.content());
                work.remove("review");
                work.remove("score");
                work.remove("reviewFeedback");
                work.remove("referenceValidation");
                work.put("consistencyRetryCount", 0);
                work.put("route", "REVIEW");
            } else if (work.get("writeResult") instanceof ReportWriterOutputConverter.Unavailable unavailable) {
                work.remove("title");
                work.remove("content");
                work.remove("review");
                work.remove("score");
                work.remove("referenceValidation");
                work.put("REFERENCE_UNAVAILABLE", Map.of(
                        "message", unavailable.message(),
                        "unavailableReferences", unavailable.unavailableReferences(),
                        "suggestion", "请核对资料；需要时重新取得数据并更新参数。"));
                work.put("status", "FAILED");
                work.put("route", "END");
            } else {
                fail(work, "WRITE_FAILED", "报告节点未返回有效结果。");
            }
            work.remove("writeResult");
            return Map.of(ReportGraph.WORK, work);
        }
    }

    /** 在同一个审核线程中审核最新报告或重新确认矛盾。 */
    static final class Review implements NodeAction {
        private final Reviewer reviewer;
        private final ReportProgress progress;

        Review(Reviewer reviewer) {
            this(reviewer, null);
        }

        Review(Reviewer reviewer, ReportProgress progress) {
            this.reviewer = reviewer;
            this.progress = progress;
        }

        @Override
        public Map<String, Object> apply(OverAllState state) {
            var work = ReportNodes.work(state);
            String feedback = (String) work.remove("reviewFeedback");
            if (progress != null) {
                if (feedback == null) {
                    int revisions = ((Number) work.getOrDefault("revisionCount", 0)).intValue();
                    progress.stage("REVIEWING", revisions == 0
                            ? "报告编写完成，开始审阅报告。"
                            : "报告修改完成，开始重新审阅。");
                } else {
                    progress.stage("REVIEW_RETRY", "正在重新核对审核结果。");
                }
            }
            work.remove("review");
            work.remove("score");
            work.remove("route");
            try {
                ReportReview review = reviewer.review((String) work.get("reportInput"),
                        "报告标题：" + work.get("title") + "\n\n" + work.get("content"), feedback);
                if (review == null || review.score() == null || !Double.isFinite(review.score())
                        || review.score() < 0 || review.score() > 10 || review.hasReferenceIssue() == null
                        || review.strengths() == null || review.issues() == null
                        || (review.score() < 8 && review.issues().isEmpty())) {
                    throw new ReportReviewValidationHook.InvalidReviewOutputException("审核结果格式无效");
                }
                work.put("review", review);
                work.put("score", review.score());
            } catch (Exception error) {
                log.warn("报告审核失败 errorType={}", error.getClass().getSimpleName());
                if (causedBy(error, ReportReviewValidationHook.InvalidReviewOutputException.class)) {
                    fail(work, "REVIEW_FORMAT_FAILED", "审核输出格式多次修正后仍然无效。");
                } else {
                    fail(work, "REVIEW_FAILED", "报告审核失败，请稍后重试。");
                }
            }
            return Map.of(ReportGraph.WORK, work);
        }
    }

    /** 判断审阅分数和引用问题是否矛盾，再决定通过、返修或结束。 */
    static final class ReviewDecide implements NodeAction {
        private final ReportProgress progress;

        ReviewDecide() {
            this(null);
        }

        ReviewDecide(ReportProgress progress) {
            this.progress = progress;
        }

        @Override
        public Map<String, Object> apply(OverAllState state) {
            var work = ReportNodes.work(state);
            if (work.containsKey("REVIEW_FAILED") || work.containsKey("REVIEW_FORMAT_FAILED")) {
                work.put("route", "END");
                return Map.of(ReportGraph.WORK, work);
            }
            ReportReview review = (ReportReview) work.get("review");
            if (review == null) {
                fail(work, "REVIEW_FAILED", "审核节点未返回有效结果。");
                return Map.of(ReportGraph.WORK, work);
            }
            int consistency = ((Number) work.getOrDefault("consistencyRetryCount", 0)).intValue();
            int revisions = ((Number) work.getOrDefault("revisionCount", 0)).intValue();
            if (Boolean.TRUE.equals(review.hasReferenceIssue()) && review.score() >= 8) {
                if (consistency >= MAX_CONSISTENCY_RETRIES) {
                    fail(work, "REVIEW_INCONSISTENT", "审核评分与引用问题多次矛盾，无法确认结果。");
                } else {
                    work.put("consistencyRetryCount", consistency + 1);
                    work.put("reviewFeedback", "你返回的审核结果存在矛盾：hasReferenceIssue 为 true，但 score 为 "
                            + review.score() + "。请重新核对引用问题和评分，确认有问题时评分应低于 8 分。");
                    work.put("route", "REVIEW");
                }
            } else if (review.score() < 8) {
                if (revisions >= MAX_REVISIONS) {
                    fail(work, "REVIEW_FAILED", "报告经过多次返修仍未通过审核。");
                } else {
                    StringBuilder feedback = new StringBuilder("请根据以下审阅意见修改上一版报告，并重新输出完整报告：\n");
                    if (!review.strengths().isEmpty()) {
                        feedback.append("报告已有的优点，请在修改时保留：\n");
                        for (String strength : review.strengths()) {
                            feedback.append("- ").append(strength).append("\n");
                        }
                    }
                    for (ReportReviewIssue issue : review.issues()) {
                        feedback.append("问题：").append(issue.problem()).append("\n原因：").append(issue.reason())
                                .append("\n建议：").append(issue.suggest()).append("\n");
                    }
                    if (progress != null) {
                        String problem = review.issues().getFirst().problem().replaceAll("[\\p{Cntrl}]", " ").strip();
                        if (problem.length() > 80) {
                            problem = problem.substring(0, 80) + "…";
                        }
                        progress.stage("REVISING", "审阅发现报告需要修改：" + problem);
                    }
                    work.put("writerFeedback", feedback.toString());
                    work.put("revisionCount", revisions + 1);
                    work.put("route", "WRITE");
                }
            } else {
                work.put("status", "COMPLETED");
                work.put("route", "END");
            }
            return Map.of(ReportGraph.WORK, work);
        }
    }

    /** 不交付错误情况下残留的旧报告。 */
    private static void fail(Map<String, Object> work, String key, String message) {
        work.remove("title");
        work.remove("content");
        work.remove("referenceValidation");
        work.remove("review");
        work.remove("score");
        work.remove("writeResult");
        work.remove("writerFeedback");
        work.remove("reviewFeedback");
        work.put(key, Map.of("message", message));
        work.put("status", "FAILED");
        work.put("route", "END");
    }

    /** 框架可能包装节点异常，保留原异常分类。 */
    private static boolean causedBy(Throwable error, Class<? extends Throwable> type) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}
