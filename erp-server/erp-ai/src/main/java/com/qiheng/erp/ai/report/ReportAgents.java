package com.qiheng.erp.ai.report;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.AgentModelFailure;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.report.graph.ReportGraph;
import org.springframework.ai.chat.model.ChatModel;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** 报告专家和审核专家共用结构，每个报告任务拥有独立的对话上下文。 */
public final class ReportAgents {
    private final ReactAgent writer;
    private final ReactAgent reviewer;
    private final MemorySaver writerMemory = new MemorySaver();
    private final MemorySaver reviewerMemory = new MemorySaver();
    private final ReportWriterOutputConverter writerConverter;
    private final ReportReviewValidationHook reviewHook;
    private final ObjectMapper mapper;

    public ReportAgents(ChatModel model, ReportReadTools tools, ObjectMapper mapper,
                        String writerPrompt, String reviewerPrompt) {
        this.mapper = mapper;
        this.writerConverter = new ReportWriterOutputConverter(mapper);
        this.reviewHook = new ReportReviewValidationHook(mapper);
        this.writer = ReactAgent.builder().name("report_writer").model(model)
                .systemPrompt(writerPrompt + "\n\n" + writerConverter.outputFormat())
                .methodTools(tools).interceptors(new TrustedToolContextInterceptor(), new ReportModelFailureInterceptor())
                .hooks(new ReportWriterFormatHook(writerConverter))
                .parallelToolExecution(false).saver(writerMemory).releaseThread(false).build();
        this.reviewer = ReactAgent.builder().name("report_reviewer").model(model)
                .systemPrompt(reviewerPrompt).outputType(ReportReview.class)
                .methodTools(tools).interceptors(new TrustedToolContextInterceptor(), new ReportModelFailureInterceptor())
                .hooks(reviewHook).parallelToolExecution(false).saver(reviewerMemory).releaseThread(false).build();
    }

    /** 创建一次报告任务；关闭时释放两位专家的内存上下文。 */
    public Task openTask(AgentToolContext identity) {
        return openTask(identity, null);
    }

    /** 进度与可信身份都绑定当前任务，不由模型提供。 */
    public Task openTask(AgentToolContext identity, ReportProgress progress) {
        if (identity == null) {
            throw new IllegalArgumentException("缺少可信用户与会话身份");
        }
        return new Task(identity, UUID.randomUUID().toString(), progress);
    }

    public final class Task implements AutoCloseable {
        private final AgentToolContext identity;
        private final String writerThread;
        private final String reviewerThread;
        private final ReportProgress progress;
        private boolean writerStarted;
        private boolean reviewerStarted;

        private Task(AgentToolContext identity, String taskId, ReportProgress progress) {
            this.identity = identity;
            this.progress = progress;
            this.writerThread = "report:" + taskId + ":writer";
            this.reviewerThread = "report:" + taskId + ":reviewer";
        }

        /** 首次传入完整材料；返修时沿用上下文并传入本轮意见。 */
        public ReportWriterOutputConverter.Output write(String input, String feedback) throws Exception {
            String request = writerStarted ? feedback : input;
            writerStarted = true;
            var config = config(writerThread, ReportWriterFormatHook.REPAIR_COUNT);
            String answer = writer.call(request, config).getText();
            if (answer == null || answer.isBlank() || AgentModelFailure.isFailure(answer)) {
                throw new IllegalStateException("报告专家调用失败");
            }
            return writerConverter.parse(answer);
        }

        /** 每一版报告都传给审核专家；同版评分矛盾时仅反馈矛盾。 */
        public ReportReview review(String input, String content, String feedback) throws Exception {
            String request;
            if (feedback != null) {
                request = feedback;
            } else if (reviewerStarted) {
                request = "请审核修改后的完整报告，以本版为准：\n" + content;
            } else {
                request = input + "\n\n请审核以下报告：\n" + content;
            }
            reviewerStarted = true;
            var config = config(reviewerThread, ReportReviewValidationHook.REPAIR_COUNT);
            String answer = reviewer.call(request, config).getText();
            if (answer == null || answer.isBlank() || AgentModelFailure.isFailure(answer)) {
                throw new IllegalStateException("审核专家调用失败");
            }
            return reviewHook.parse(answer);
        }

        private RunnableConfig config(String thread, String counter) {
            var builder = RunnableConfig.builder().threadId(thread)
                    .addMetadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT, identity)
                    .addMetadata(counter, new AtomicInteger());
            if (progress != null) {
                builder.addMetadata(ReportProgress.METADATA_KEY, progress);
            }
            return builder.build();
        }

        @Override
        public void close() throws Exception {
            try {
                writerMemory.release(config(writerThread, ReportWriterFormatHook.REPAIR_COUNT));
            } finally {
                reviewerMemory.release(config(reviewerThread, ReportReviewValidationHook.REPAIR_COUNT));
            }
        }
    }

    /** 兼容原有独立报告图入口。 */
    public String write(Map<String, Object> material, AgentToolContext identity) throws Exception {
        try (Task task = openTask(identity)) {
            var output = task.write(mapper.writeValueAsString(material), null);
            if (output instanceof ReportWriterOutputConverter.Completed completed) {
                return completed.content();
            }
            throw new IllegalStateException("报告所需资料不可用");
        }
    }

    /** 兼容旧入口，新的任务流程使用 Task 维持上下文。 */
    public String writeReport(String input, String previousContent, Object review,
                              AgentToolContext identity) throws Exception {
        String request = input;
        if (review != null) {
            request += "\n上一版报告：\n" + previousContent + "\n审核意见：\n" + mapper.writeValueAsString(review);
        }
        try (Task task = openTask(identity)) {
            var output = task.write(request, null);
            if (output instanceof ReportWriterOutputConverter.Completed completed) {
                return completed.content();
            }
            throw new IllegalStateException("报告所需资料不可用");
        }
    }

    /** 保留旧图接口，内部转换为新的审核结构。 */
    public ReportGraph.Review review(Map<String, Object> material, AgentToolContext identity) throws Exception {
        try (Task task = openTask(identity)) {
            ReportReview review = task.review(mapper.writeValueAsString(material), "", null);
            var issues = review.issues().stream()
                    .map(issue -> new ReportGraph.Issue("", issue.problem(), issue.reason(), issue.suggest())).toList();
            return new ReportGraph.Review(review.score(), review.strengths(), issues);
        }
    }
}
