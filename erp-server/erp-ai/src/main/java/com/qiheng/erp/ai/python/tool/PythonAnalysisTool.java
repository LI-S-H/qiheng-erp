package com.qiheng.erp.ai.python.tool;

import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.qiheng.erp.ai.analysis.AnalysisTaskContext;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.python.sandbox.PythonExecutionResult;
import com.qiheng.erp.ai.python.sandbox.PythonSandboxSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 共享的 Python 工具入口；任务数据和沙箱保存在可信任务上下文。
 *
 * @author Li
 * @since 2026-11-10
 */
@Component
public final class PythonAnalysisTool {
    private static final Logger log = LoggerFactory.getLogger(PythonAnalysisTool.class);
    private final PythonDatasetPreparer preparer;
    private final AnalysisResultStore results;

    /** 注入共享的数据准备与结果存储服务。 */
    public PythonAnalysisTool(PythonDatasetPreparer preparer, AnalysisResultStore results) {
        this.preparer = preparer;
        this.results = results;
    }

    /** 只接受服务端注入的当前任务。 */
    private AnalysisTaskContext task(ToolContext context) {
        return ToolContextHelper.getMetadata(context, AnalysisTaskContext.CONTEXT_KEY, AnalysisTaskContext.class)
                .orElseThrow(() -> new IllegalStateException("缺少可信分析任务上下文"));
    }

    /**
     * 按需准备当前会话的数据集；同一数据集在任务内复用路径。
     *
     * @param datasetIds 本次需要的数据集编号
     * @param context 运行上下文，提供可信身份
     * @return 准备结果与后续可用的文件路径
     * @throws IOException 数据集读取或写入沙箱失败
     */
    @Tool(name = "prepareAnalysisData",
            description = "为 Python 分析准备数据：将所需数据集放入当前分析任务的沙箱，"
                    + "并返回后续代码可使用的文件路径。")
    public Map<String, Object> prepareAnalysisData(
            @ToolParam(description = "需要准备的已发布数据集 ID 数组，不需要数据时传空数组")
            List<String> datasetIds, ToolContext context) throws IOException {
        var task = task(context);
        synchronized (task) {
            var prepared = task.preparedDatasets();
            var identity = task.identity();
            List<Map<String, Object>> files = new ArrayList<>();
            for (String id : datasetIds) {
                Map<String, Object> file = prepared.get(id);
                boolean reprepared = false;
                if (file != null) {
                    preparer.verify(id, identity);
                    // 沙箱容器可能被回收重建：内存里的记录不代表文件还在，核对后按需重写。
                    if (!task.sandbox().hasInput(preparer.inputFileName(id))) {
                        prepared.remove(id);
                        file = preparer.prepare(task.sandbox(), identity, id);
                        prepared.put(id, file);
                        reprepared = true;
                    }
                } else {
                    // 首次准备时 openReady 已经检查归属和当前权限。
                    file = preparer.prepare(task.sandbox(), identity, id);
                    prepared.put(id, file);
                }
                Map<String, Object> item = new LinkedHashMap<>(file);
                item.put("reprepared", reprepared);
                files.add(item);
            }
            return Map.of("status", "SUCCESS", "files", files);
        }
    }

    /**
     * 执行代码并按需保存输出；生成的文件在成功后逐个登记。
     *
     * @param code 完整的 Python 代码
     * @param datasetIds 本次代码使用的已准备数据集编号
     * @param saveResult 是否保存标准输出
     * @param outputTextName 保存标准输出时的展示名称
     * @param context 运行上下文，提供可信身份
     * @return 执行结果、文本输出与已登记的文件
     * @throws IOException 结果保存失败
     */
    @Tool(name = "executePython",
            description = "在当前分析任务的沙箱中执行 Python 代码，返回文本输出和生成的文件。"
                    + "需要交付的文件请写入 /tmp/erp-analysis/output/。"
                    + "每次执行为独立 Python 进程，变量不跨次保留。")
    public Map<String, Object> executePython(
            @ToolParam(description = "完整 Python 代码，用 print 输出文本；需交付的文件写入 /tmp/erp-analysis/output/")
            String code,
            @ToolParam(description = "本次代码使用的已准备数据集 ID 数组，无数据集时传空数组")
            List<String> datasetIds,
            @ToolParam(description = "是否保存标准输出供后续读取；输出过长时会自动保存")
            boolean saveResult,
            @ToolParam(required = false, description = "保存标准输出文本时的展示名称，不影响生成文件名；省略时使用默认名称")
            String outputTextName, ToolContext context) throws IOException {
        var task = task(context);
        synchronized (task) {
            var prepared = task.preparedDatasets();
            var identity = task.identity();
            var taskId = task.taskId();
            if (!prepared.keySet().containsAll(datasetIds)) {
                throw new IllegalArgumentException("请先准备本次使用的数据集");
            }
            // 已加载的文件都可被代码读取，保守记录并复核全部来源。
            for (String id : prepared.keySet()) {
                preparer.verify(id, identity);
            }
            var sandbox = task.sandbox();
            var scope = new ExecutionScope(identity, taskId, UUID.randomUUID().toString(),
                    List.copyOf(prepared.keySet()), sandbox);
            var before = readArtifactsBefore(scope);
            PythonExecutionResult execution;
            try {
                execution = sandbox.execute(code, 60, 8000);
            } catch (RuntimeException error) {
                // 沙箱不可用：容器丢失或通信失败，本次没有任何文件产出。
                log.warn("Python 沙箱不可用 taskId={} errorType={}", taskId, error.getClass().getName());
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("status", "FAILED");
                response.put("errorType", "SANDBOX_UNAVAILABLE");
                response.put("message", "运行环境不可用，沙箱内文件与未交付结果全部丢失；已登记的结果不受影响。"
                        + "如需继续，请重新调用 prepareAnalysisData 准备数据后再执行。");
                response.put("sandboxFiles", sandboxFiles(task, identity));
                return response;
            }
            var textOutput = new LinkedHashMap<String, Object>();
            var response = buildResponse(execution, textOutput);
            if (!execution.success()) {
                response.put("errorType", execution.errorType());
                response.put("stderr", execution.stderr());
                response.put("message", "执行失败，本次生成的文件不会保存；请根据错误修正代码并使用新文件名。");
                response.put("sandboxFiles", sandboxFiles(task, identity));
                return response;
            }
            var run = new ExecutionRun(scope, execution, before);
            return collectResults(response, textOutput, run, saveResult, outputTextName);
        }
    }

    /**
     * 汇总沙箱现状：输入文件、输出文件与已登记结果。
     *
     * <p>沙箱是临时的，重建后里面的文件全部丢失；已登记的结果文件保存在服务端，不受影响。
     * 模型据此判断该重新准备数据，还是可以直接引用已登记的结果。</p>
     *
     * @param task 当前分析任务
     * @param identity 可信身份
     * @return 三类文件的现状
     */
    private Map<String, Object> sandboxFiles(AnalysisTaskContext task, AgentToolContext identity) {
        Map<String, Object> files = new LinkedHashMap<>();
        files.put("inputs", inputFiles(task));
        files.put("outputs", outputFiles(task));
        files.put("registeredResults", registeredResults(task, identity));
        return files;
    }

    /** 输入文件的现状；沙箱重建后已准备的数据集会显示为不在。 */
    private List<Map<String, Object>> inputFiles(AnalysisTaskContext task) {
        List<Map<String, Object>> inputs = new ArrayList<>();
        for (var entry : task.preparedDatasets().entrySet()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("datasetId", entry.getKey());
            item.put("filePath", entry.getValue().get("filePath"));
            item.put("present", inputPresent(task, entry.getKey()));
            inputs.add(item);
        }
        return inputs;
    }

    /** 判断沙箱里该数据集的文件是否还在；读不到时按不在处理。 */
    private boolean inputPresent(AnalysisTaskContext task, String datasetId) {
        try {
            return task.sandbox().hasInput(preparer.inputFileName(datasetId));
        } catch (IOException | RuntimeException error) {
            log.warn("沙箱输入文件状态读取失败 taskId={} datasetId={}", task.taskId(), datasetId);
            return false;
        }
    }

    /** 沙箱输出目录里的文件；读不到时返回空列表，不让失败路径再抛一次。 */
    private List<Map<String, Object>> outputFiles(AnalysisTaskContext task) {
        List<Map<String, Object>> outputs = new ArrayList<>();
        try {
            for (var artifact : task.sandbox().listArtifacts(PythonSandboxSession.OUTPUT_DIRECTORY)) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("fileName", artifact.name());
                item.put("size", artifact.size());
                outputs.add(item);
            }
        } catch (IOException | RuntimeException error) {
            log.warn("沙箱输出目录读取失败 taskId={}", task.taskId(), error);
        }
        return outputs;
    }

    /** 已登记的服务端结果；沙箱重建不影响它们。 */
    private List<Map<String, Object>> registeredResults(AnalysisTaskContext task, AgentToolContext identity) {
        List<Map<String, Object>> registered = new ArrayList<>();
        try {
            for (var record : results.list(identity, task.taskId())) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("resultId", record.getResultId());
                item.put("name", record.getName());
                item.put("fileType", record.getFileType());
                item.put("size", record.getFileSize());
                registered.add(item);
            }
        } catch (IOException | RuntimeException error) {
            log.warn("已登记结果读取失败 taskId={}", task.taskId(), error);
        }
        return registered;
    }

    /** 读取执行前的输出目录；读取失败时仍允许运行修复代码，但不再登记文件。 */
    private List<PythonSandboxSession.SandboxArtifact> readArtifactsBefore(ExecutionScope scope) {
        try {
            return scope.sandbox().listArtifacts(PythonSandboxSession.OUTPUT_DIRECTORY);
        } catch (IOException | RuntimeException error) {
            // 输出目录异常时仍允许运行修复代码，但不能误判哪些文件是本次新生成的。
            log.warn("执行前无法读取输出目录，任务 ID={}，执行 ID={}", scope.taskId(), scope.executionId(), error);
            return null;
        }
    }

    /** 建立本次执行的基础响应；文本输出小节由调用方持有，便于后续写入保存结果。 */
    private Map<String, Object> buildResponse(PythonExecutionResult execution, Map<String, Object> textOutput) {
        var response = new LinkedHashMap<String, Object>();
        response.put("status", execution.success() ? "SUCCESS" : "FAILED");
        textOutput.put("content", execution.stdout());
        textOutput.put("bytes", execution.stdoutBytes());
        textOutput.put("truncated", execution.stdoutTruncated());
        textOutput.put("saved", false);
        response.put("textOutput", textOutput);
        return response;
    }

    /** 保存文本输出并登记本次新生成的文件；目录异常时只标记为部分完成。 */
    private Map<String, Object> collectResults(Map<String, Object> response, Map<String, Object> textOutput,
            ExecutionRun run, boolean saveResult, String outputTextName) throws IOException {
        var output = new ExecutionOutput();
        response.put("files", output.files());
        try {
            // 标准输出与生成文件分开处理；目录异常不应导致文本结果丢失。
            if (saveResult || run.execution().stdoutTruncated()) {
                String name = outputTextName == null || outputTextName.isBlank() ? "Python 分析结果" : outputTextName;
                saveStandardOutput(response, textOutput, run, name);
            }
            var artifacts = run.scope().sandbox().listArtifacts(PythonSandboxSession.OUTPUT_DIRECTORY);
            output.directoryScanned(true);
            if (run.before() == null) {
                response.put("status", "PARTIAL");
                response.put("pendingFilesKnown", false);
                response.put("message", "执行前无法读取输出目录，无法确认本次生成的文件。"
                        + "请清理无效项目或超限文件后，重新生成需要保存的文件。");
                return response;
            }
            for (var file : artifacts) {
                if (!run.before().contains(file)) {
                    output.pendingFiles().add(file.name());
                }
            }
            registerNewFiles(run, artifacts, output);
        } catch (IOException | RuntimeException error) {
            log.warn("Python 分析结果保存不完整，任务 ID={}，执行 ID={}", run.scope().taskId(),
                    run.scope().executionId(), error);
            response.put("status", "PARTIAL");
            response.put("pendingFiles", output.pendingFiles());
            response.put("pendingFilesKnown", output.directoryScanned() && run.before() != null);
            response.put("message", "代码已执行，但输出目录无法读取或结果保存不完整；已登记的 ID 仍可使用。"
                    + "请检查目录中是否包含子目录、链接或超限文件，清理后用新文件名重新生成未保存的文件。");
            return response;
        }
        response.put("message", response.containsKey("message") ? response.get("message")
                : "本次执行完成。");
        return response;
    }

    /** 保存标准输出文本；输出过长时同样保存完整内容。 */
    private void saveStandardOutput(Map<String, Object> response, Map<String, Object> textOutput,
            ExecutionRun run, String name) throws IOException {
        var scope = run.scope();
        var saved = results.save(scope.identity(), scope.taskId(), scope.executionId(), "TEXT", name, null,
                "txt", scope.sources(), out -> scope.sandbox().readOutput(run.execution(), out));
        textOutput.put("resultId", saved.getResultId());
        textOutput.put("name", name);
        textOutput.put("saved", true);
        if (run.execution().stdoutTruncated()) {
            response.put("message", "标准输出过长，已保存完整输出；当前只显示预览，可通过结果 ID 读取。");
        }
    }

    /** 登记本次新生成的文件；执行前已存在的文件不重复保存。 */
    private void registerNewFiles(ExecutionRun run, List<PythonSandboxSession.SandboxArtifact> artifacts,
            ExecutionOutput output) throws IOException {
        var scope = run.scope();
        for (var file : artifacts) {
            if (run.before().contains(file)) {
                continue;
            }
            String extension = extension(file.name());
            var saved = results.save(scope.identity(), scope.taskId(), scope.executionId(), "FILE", file.name(),
                    null, extension, scope.sources(),
                    out -> scope.sandbox().readArtifact(PythonSandboxSession.OUTPUT_DIRECTORY, file, out));
            var info = new LinkedHashMap<String, Object>();
            info.put("resultId", saved.getResultId());
            info.put("fileName", file.name());
            info.put("fileType", extension);
            info.put("size", file.size());
            output.files().add(info);
            output.pendingFiles().remove(file.name());
        }
    }

    /** 从展示文件名提取受支持长度的扩展名。 */
    private String extension(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return null;
        }
        String type = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        return type.matches("[a-z0-9]{1,32}") ? type : null;
    }

    /** 本次执行涉及的身份、任务与来源；保存结果时统一复用。 */
    private record ExecutionScope(AgentToolContext identity, String taskId, String executionId,
                                   List<String> sources, PythonSandboxSession sandbox) {
    }

    /** 一次执行的完整上下文：作用域、执行结果与执行前的文件清单。 */
    private record ExecutionRun(ExecutionScope scope, PythonExecutionResult execution,
                                List<PythonSandboxSession.SandboxArtifact> before) {
    }

    /** 本次执行登记的中间产物：文件清单、待确认文件与目录扫描状态。 */
    private static final class ExecutionOutput {
        private final List<Map<String, Object>> files = new ArrayList<>();
        private final List<String> pendingFiles = new ArrayList<>();
        private boolean directoryScanned;

        /** 已登记的文件清单。 */
        List<Map<String, Object>> files() {
            return files;
        }

        /** 未能确认归属的文件名。 */
        List<String> pendingFiles() {
            return pendingFiles;
        }

        /** 输出目录是否读取成功。 */
        boolean directoryScanned() {
            return directoryScanned;
        }

        /** 标记输出目录是否读取成功。 */
        void directoryScanned(boolean directoryScanned) {
            this.directoryScanned = directoryScanned;
        }
    }
}
