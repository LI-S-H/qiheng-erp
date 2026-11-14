package com.qiheng.erp.ai.analysis;

import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.python.sandbox.PythonSandboxRuntime;
import com.qiheng.erp.ai.python.sandbox.PythonSandboxSession;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 单次分析任务的数据及按需创建的沙箱，由任务入口负责关闭。
 *
 * @author Li
 * @since 2026-11-10
 */
public final class AnalysisTaskContext implements AutoCloseable {
    public static final String CONTEXT_KEY = "analysisTask";
    private final AgentToolContext identity;
    private final String taskId;
    private final PythonSandboxRuntime runtime;
    private final Map<String, Map<String, Object>> preparedDatasets = new LinkedHashMap<>();
    private final Set<String> usedDatasetIds = new LinkedHashSet<>();
    private PythonSandboxSession sandbox;
    private boolean closed;

    /** 创建轻量任务上下文，不启动沙箱。 */
    public AnalysisTaskContext(AgentToolContext identity, String taskId, PythonSandboxRuntime runtime) {
        this.identity = identity;
        this.taskId = taskId;
        this.runtime = runtime;
        runtime.startTask(taskId);
    }

    /** 取得服务端提供的可信身份。 */
    public AgentToolContext identity() {
        return identity;
    }

    /** 取得本次分析任务 ID。 */
    public String taskId() {
        return taskId;
    }

    /** 分析过程发生变化时刷新沙箱活动时间。 */
    public void touch() {
        runtime.touchTask(taskId);
    }

    /** 取得本任务已准备的数据集，调用方须持有任务锁。 */
    public synchronized Map<String, Map<String, Object>> preparedDatasets() {
        if (closed) {
            throw new IllegalStateException("分析任务已结束");
        }
        return preparedDatasets;
    }

    /** 仅记录本次实际成功查询或读取的数据集。 */
    public synchronized void recordDataset(String id) {
        if (id != null && !id.isBlank()) {
            usedDatasetIds.add(id);
        }
    }

    /** 返回任务内的已使用数据集，不包含仅展示的目录。 */
    public synchronized List<String> usedDatasetIds() {
        return List.copyOf(usedDatasetIds);
    }

    /** 本任务首次使用 Python 时创建，后续复用同一会话。 */
    public synchronized PythonSandboxSession sandbox() {
        if (closed) {
            throw new IllegalStateException("分析任务已结束");
        }
        if (sandbox == null) {
            sandbox = runtime.openSession(taskId);
        }
        return sandbox;
    }

    /** 任务结束时释放沙箱；未使用 Python 时无需创建。 */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (sandbox != null) {
                sandbox.close();
            }
        } finally {
            runtime.finishTask(taskId);
        }
    }
}
