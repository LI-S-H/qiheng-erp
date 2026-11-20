package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.AgentModelFailure;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.agent.context.ContextCompressionFactory;
import com.qiheng.erp.ai.agent.interceptor.ToolJsonInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.AnalysisDatasetSummaryService;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.tool.ListDatasetsTool;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.python.sandbox.PythonSandboxRuntime;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.python.tool.PythonAnalysisTool;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 独立执行一次分析任务；任务结束后释放专属 Python 沙箱。
 *
 * <p>分析智能体与主智能体各自持有会话，但共享同一套可信身份、数据集权限和沙箱运行时。
 * 主智能体通过 {@link AnalysisCommand} 交办任务，本服务负责组装输入、执行分析并交回结论。</p>
 *
 * @author Li
 * @since 2026-11-10
 */
public final class AnalysisAgentService {
    private static final Logger log = LoggerFactory.getLogger(AnalysisAgentService.class);

    /** 模型调用失败时的错误码，前端据此区分瞬时故障与业务失败。 */
    private static final String ANALYSIS_MODEL_FAILED = "ANALYSIS_MODEL_FAILED";

    private final ReactAgent agent;
    private final PythonSandboxRuntime sandboxRuntime;
    private final AiAgentTraceMapper traces;
    private final ObjectMapper mapper;
    private final DatasetManager datasetManager;
    private final AnalysisResultStore resultStore;
    private final AnalysisDatasetSummaryService datasetSummaries;
    private final AnalysisHandoffValidationHook handoffValidation;

    /**
     * 组装分析智能体；工具齐备时同时开放 SQL 查询与数据集读取能力。
     *
     * @param model 分析使用的模型
     * @param pythonTool Python 执行工具
     * @param sandboxRuntime 沙箱运行时
     * @param prompt 分析智能体的角色提示
     * @param traces 分析流水记录器
     * @param mapper JSON 序列化组件
     * @param sql SQL 查询工具，可为空
     * @param readDatasetTool 数据集读取工具，可为空
     * @param jsonGuard 工具参数校验拦截器
     * @param listDatasetsTool 数据集列表工具，可为空
     * @param results 分析结果读写工具
     * @param datasetManager 数据集管理器，可为空
     * @param resultStore 分析结果存储，可为空
     * @param compression 上下文压缩工厂，可为空
     * @param additionalInterceptors 追加的拦截器
     */
    public AnalysisAgentService(ChatModel model, PythonAnalysisTool pythonTool, PythonSandboxRuntime sandboxRuntime,
                                String prompt, AiAgentTraceMapper traces, ObjectMapper mapper, SqlQueryTool sql,
                                ReadDatasetTool readDatasetTool, ToolJsonInterceptor jsonGuard,
                                ListDatasetsTool listDatasetsTool, AnalysisResultTool results,
                                DatasetManager datasetManager, AnalysisResultStore resultStore,
                                ContextCompressionFactory compression, Interceptor... additionalInterceptors) {
        this.sandboxRuntime = sandboxRuntime;
        this.traces = traces;
        this.mapper = mapper;
        this.datasetManager = datasetManager;
        this.resultStore = resultStore;
        this.datasetSummaries = datasetManager == null ? null : new AnalysisDatasetSummaryService(datasetManager);
        this.handoffValidation = new AnalysisHandoffValidationHook(mapper);
        var interceptors = new ArrayList<Interceptor>(List.of(new TrustedToolContextInterceptor(), jsonGuard,
                new AnalysisDatasetInterceptor(mapper), new AnalysisModelFailureInterceptor()));
        interceptors.addAll(List.of(additionalInterceptors));
        var hooks = new ArrayList<Hook>(List.of(new AnalysisTraceHook(), handoffValidation));
        if (compression != null) {
            hooks.add(compression.create());
        }
        var builder = ReactAgent.builder()
                .name("analysis_agent")
                .model(serialModel(model))
                .systemPrompt(prompt)
                .outputType(AnalysisHandoff.class)
                .hooks(hooks)
                .interceptors(interceptors.toArray(Interceptor[]::new))
                .parallelToolExecution(false);
        if (sql != null && readDatasetTool != null && listDatasetsTool != null) {
            builder.methodTools(pythonTool, results, sql, readDatasetTool, listDatasetsTool);
        } else {
            builder.methodTools(pythonTool, results);
        }
        this.agent = builder.build();
    }

    /**
     * 使用服务端身份运行独立任务；输入可包含已知的数据集说明。
     *
     * @param goal 交办的分析目标
     * @param identity 服务端确认的可信身份
     * @return 分析结论文字
     * @throws GraphRunnerException 图运行失败
     */
    public String analyze(String goal, AgentToolContext identity) throws GraphRunnerException {
        try {
            var command = new AnalysisCommand(goal, goal, identity, null);
            return execute(new AnalysisRun(command, goal, UUID.randomUUID().toString(), false, List.of())).summary();
        } catch (IOException error) {
            throw new IllegalStateException("分析结果处理失败", error);
        }
    }

    /**
     * 在服务内拼装当前会话数据集并向主智能体返回结构化结果。
     *
     * @param command 本次分析任务的请求参数
     * @return 结构化分析结果
     */
    public AnalysisResult analyzeForMainAgent(AnalysisCommand command) {
        String taskId = UUID.randomUUID().toString();
        boolean started = false;
        try {
            String goal = requireGoal(command);
            requireDataServices();
            var modelInput = buildModelInput(command, goal);
            started = true;
            var result = execute(new AnalysisRun(command, modelInput.payload(), taskId, true, modelInput.provided()));
            publishSummary(command.progress(), result);
            return result;
        } catch (GraphRunnerException | IOException | RuntimeException error) {
            log.warn("分析任务执行失败 taskId={} errorType={}", taskId, error.getClass().getName());
            if (!started) {
                // 任务尚未开始时补记输入与失败阶段，便于后续排查准备阶段的问题。
                var recorder = traceRecorder(command.identity(), taskId);
                recorder.recordText("INPUT", command.goal());
                recorder.record("ERROR", Map.of("type", error.getClass().getName(), "stage", "准备分析输入"));
            }
            String code = command.goal() == null || command.goal().isBlank()
                    ? "INVALID_ANALYSIS_GOAL" : "ANALYSIS_INPUT_FAILED";
            String reason = code.equals("INVALID_ANALYSIS_GOAL") ? "请提供具体的分析目标后再调用。"
                    : "准备当前会话数据集信息失败，请稍后重试或使用其他可用工具。";
            var result = new AnalysisResult("FAILED", taskId, reason, List.of(), List.of(), List.of(), code);
            publishSummary(command.progress(), result);
            return result;
        }
    }

    /** 分析目标必须是可执行的具体描述。 */
    private String requireGoal(AnalysisCommand command) {
        String goal = command.goal();
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("分析目标不能为空");
        }
        return goal;
    }

    /** 数据集与结果存储缺失时无法交回可用结论，直接失败。 */
    private void requireDataServices() {
        if (datasetSummaries == null || resultStore == null) {
            throw new IllegalStateException("分析数据服务不可用");
        }
    }

    /**
     * 组装交给分析模型的输入；已知条件与当前会话可用的数据集一并写入。
     *
     * @param command 本次分析任务的请求参数
     * @param goal 已确认的分析目标
     * @return 模型输入与本次可用的数据集说明
     * @throws IOException 输入序列化失败
     */
    private ModelInput buildModelInput(AnalysisCommand command, String goal) throws IOException {
        var input = new LinkedHashMap<String, Object>();
        input.put("goal", goal);
        if (command.context() != null && !command.context().isBlank()) {
            input.put("context", command.context());
        }
        var available = datasetSummaries.listForAnalysis(command.identity());
        input.put("availableDatasets", available);
        return new ModelInput(mapper.writeValueAsString(input), toResultDatasets(available.datasets()));
    }

    /** 把数据集说明转换为交回主智能体的结构。 */
    private List<AnalysisResult.Dataset> toResultDatasets(List<AnalysisDatasetSummaryService.Item> items) {
        var datasets = new ArrayList<AnalysisResult.Dataset>();
        for (var item : items) {
            datasets.add(new AnalysisResult.Dataset(item.datasetId(), item.description(), item.rowCount(),
                    item.completed(), item.completenessMessage()));
        }
        return datasets;
    }

    /** 把最终结论发布给前端；与返回主智能体的结果保持同一份内容。 */
    private void publishSummary(AnalysisProgress progress, AnalysisResult result) {
        if (progress != null) {
            progress.summary(result.completion(), result.summary());
        }
    }

    /**
     * 建立本次分析任务的流水记录器。
     *
     * @param identity 服务端确认的可信身份
     * @param taskId 本次分析任务编号
     * @return 分析智能体的流水记录器
     */
    private AiAgentTraceRecorder traceRecorder(AgentToolContext identity, String taskId) {
        return new AiAgentTraceRecorder(traces, mapper,
                new AiAgentTraceRecorder.Scope(identity.userId(), identity.conversationId(), taskId, "ANALYSIS"));
    }

    /** 单次分析运行所需的全部参数。 */
    private record AnalysisRun(AnalysisCommand command, String modelInput, String taskId,
                               boolean includeResults, List<AnalysisResult.Dataset> provided) {
    }

    /** 交给模型的输入内容与本次可用的数据集说明。 */
    private record ModelInput(String payload, List<AnalysisResult.Dataset> provided) {
    }

    /**
     * 单个任务运行和最终流水只执行一次；工具调用按任务收集数据集。
     *
     * @param run 本次运行参数
     * @return 分析结果
     * @throws GraphRunnerException 图运行失败
     * @throws IOException 结果读写失败
     */
    private AnalysisResult execute(AnalysisRun run) throws GraphRunnerException, IOException {
        var command = run.command();
        var identity = command.identity();
        var recorder = traceRecorder(identity, run.taskId());
        recorder.recordText("INPUT", command.goal());
        var task = new AnalysisTaskContext(identity, run.taskId(), sandboxRuntime);
        try (task) {
            var config = buildConfig(run, task, recorder);
            String answer = agent.call(run.modelInput(), config).getText();
            // 模型调用失败时框架返回错误文本，它不是结论；单独记录并给出可读说明。
            if (AgentModelFailure.isFailure(answer)) {
                recorder.recordText("MODEL_FAILURE", answer);
                return modelFailed(task, run, answer);
            }
            // 先保存模型原始答复，格式校验失败时仍可追溯。
            recorder.recordText("FINAL", answer);
            AnalysisHandoff handoff = handoffValidation.parse(answer);
            if (!run.includeResults()) {
                return new AnalysisResult(handoff.completion(), run.taskId(), handoff.summary(),
                        List.of(), List.of(), List.of(), null);
            }
            return assemble(task, handoff, run.provided());
        } catch (GraphRunnerException | IOException | RuntimeException error) {
            recorder.record("ERROR", Map.of("type", error.getClass().getName(),
                    "text", String.valueOf(error.getMessage())));
            if (run.includeResults()) {
                return failed(task, run.provided(), error);
            }
            throw error;
        }
    }

    /**
     * 模型调用失败时返回可读说明，并保留中断前已取得的数据和结果。
     *
     * @param task 本次任务上下文
     * @param run 本次运行参数
     * @param answer 框架返回的失败文本
     * @return 标注失败的分析结果
     */
    private AnalysisResult modelFailed(AnalysisTaskContext task, AnalysisRun run, String answer) {
        String summary = modelFailureSummary(run, AgentModelFailure.reason(answer), task);
        if (!run.includeResults()) {
            return new AnalysisResult("FAILED", task.taskId(), summary, List.of(), List.of(), List.of(),
                    ANALYSIS_MODEL_FAILED);
        }
        try {
            var partial = assemble(task, new AnalysisHandoff("FAILED", summary), run.provided());
            return new AnalysisResult("FAILED", task.taskId(), summary, partial.datasets(),
                    partial.providedDatasets(), partial.analysisResults(), ANALYSIS_MODEL_FAILED);
        } catch (IOException | RuntimeException error) {
            return new AnalysisResult("FAILED", task.taskId(), summary, List.of(), List.of(), List.of(),
                    ANALYSIS_MODEL_FAILED);
        }
    }

    /**
     * 组装失败说明：失败轮次、原始原因、已完成的内容与建议。
     *
     * @param run 本次运行参数
     * @param reason 模型失败的原始原因
     * @param task 本次任务上下文
     * @return 面向用户与主智能体的说明
     */
    private String modelFailureSummary(AnalysisRun run, String reason, AnalysisTaskContext task) {
        var lines = new ArrayList<String>();
        lines.add("分析未完成：模型调用失败");
        lines.add("");
        var progress = run.command().progress();
        int steps = progress == null ? 0 : progress.stepCount();
        if (steps > 0) {
            lines.add("· 失败位置：第 " + (steps + 1) + " 轮，前面 " + steps + " 轮已完成");
        }
        lines.add("· 原始原因：" + reason);
        var used = task.usedDatasetIds();
        if (!used.isEmpty()) {
            lines.add("· 已经完成：已读取 " + used.size() + " 个数据集（" + String.join("、", used) + "）");
        }
        lines.add("· 建议：稍后重试；若目标不变可直接重新发起，本次会重新准备数据。");
        return String.join("\n", lines);
    }

    /**
     * 装配本次运行的配置；过程通道与业务日期只在服务端注入。
     *
     * @param run 本次运行参数
     * @param task 本次任务的数据与沙箱上下文
     * @param recorder 本次任务的流水记录器
     * @return 运行配置
     */
    private RunnableConfig buildConfig(AnalysisRun run, AnalysisTaskContext task, AiAgentTraceRecorder recorder) {
        var identity = run.command().identity();
        var configBuilder = RunnableConfig.builder()
                .threadId(run.taskId())
                .addMetadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT, identity)
                .addMetadata(AnalysisTaskContext.CONTEXT_KEY, task)
                .addMetadata(AnalysisTraceHook.RECORDER_KEY, recorder)
                .addMetadata(ToolJsonInterceptor.ERROR_COUNT, new AtomicInteger())
                .addMetadata(AnalysisHandoffValidationHook.REPAIR_COUNT, new AtomicInteger())
                .addMetadata(AnalysisResultTool.CONTEXT_TASK_ID, run.taskId())
                .addMetadata("runId", run.taskId());
        // 过程通道由主智能体侧传入；独立调用时为空，不产生任何过程事件。
        if (run.command().progress() != null) {
            configBuilder.addMetadata(AnalysisProgress.CONTEXT_KEY, run.command().progress());
        }
        // 分析与主智能体使用同一轮冻结的业务日期。
        if (identity.businessDate() != null) {
            configBuilder.addMetadata("businessDate", identity.businessDate());
        }
        return configBuilder.build();
    }

    /**
     * 返回已校验的数据引用；目录和预览不被当作本次使用的证据。
     *
     * @param task 本次任务上下文
     * @param handoff 模型交回的结论
     * @param provided 本次可用的数据集说明
     * @return 附带数据与文件的分析结果
     * @throws IOException 结果文件读取失败
     */
    private AnalysisResult assemble(AnalysisTaskContext task, AnalysisHandoff handoff,
                                     List<AnalysisResult.Dataset> provided) throws IOException {
        var identity = task.identity();
        for (var item : provided) {
            datasetManager.get(item.datasetId(), identity.userId(), identity.conversationId());
        }
        var datasets = new ArrayList<AnalysisResult.Dataset>();
        for (String id : task.usedDatasetIds()) {
            var record = datasetManager.get(id, identity.userId(), identity.conversationId());
            datasets.add(new AnalysisResult.Dataset(id, record.description(), record.rowCount(),
                    record.completed(), record.completenessMessage()));
        }
        var files = resultStore.list(identity, task.taskId()).stream()
                .map(item -> new AnalysisResult.File(item.getResultId(), item.getName(), item.getResultType(),
                        item.getFileType(), item.getFileSize()))
                .toList();
        return new AnalysisResult(handoff.completion(), task.taskId(), handoff.summary(),
                List.copyOf(datasets), provided, files, null);
    }

    /**
     * 任务失败时尽力保留已经成功保存的文件，并提示按任务 ID 查询。
     *
     * @param task 本次任务上下文
     * @param provided 本次可用的数据集说明
     * @param cause 失败原因
     * @return 标注失败的分析结果
     */
    private AnalysisResult failed(AnalysisTaskContext task, List<AnalysisResult.Dataset> provided, Exception cause) {
        String code = cause instanceof AnalysisHandoffValidationHook.InvalidHandoffException ? "INVALID_ANALYSIS_OUTPUT"
                : cause instanceof IOException ? "ANALYSIS_IO_FAILED" : "ANALYSIS_EXECUTION_FAILED";
        String reason = cause instanceof AnalysisHandoffValidationHook.InvalidHandoffException ? "分析结果格式无效，无法确认结论。"
                : cause instanceof IOException ? "分析结果读取或保存失败。" : "分析执行或资源清理过程中断。";
        try {
            var partial = assemble(task, new AnalysisHandoff("PARTIAL", reason), provided);
            return new AnalysisResult("FAILED", task.taskId(), reason + "以上是中断前已取得的数据和已保存的结果。",
                    partial.datasets(), partial.providedDatasets(), partial.analysisResults(), code);
        } catch (IOException | RuntimeException error) {
            return new AnalysisResult("FAILED", task.taskId(), reason + "已保存结果暂无法确认。",
                    List.of(), List.of(), List.of(), code);
        }
    }

    /** 沙箱工具共享任务目录，要求模型串行提出工具调用。 */
    private ChatModel serialModel(ChatModel model) {
        if (!(model instanceof OpenAiChatModel openAi)) {
            return model;
        }
        var options = ((OpenAiChatOptions) openAi.getDefaultOptions()).copy();
        options.setParallelToolCalls(false);
        return openAi.mutate().defaultOptions(options).build();
    }
}
