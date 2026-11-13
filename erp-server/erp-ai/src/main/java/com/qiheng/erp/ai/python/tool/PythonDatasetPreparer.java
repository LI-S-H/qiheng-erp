package com.qiheng.erp.ai.python.tool;

import com.qiheng.erp.ai.python.sandbox.PythonSandboxSession;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.context.AgentToolContext;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将当前用户授权的数据集及其列信息准备到分析任务沙箱。
 *
 * @author Li
 * @since 2026-11-10
 */
@Component
public final class PythonDatasetPreparer {
    private final DatasetManager datasets;
    /** 注入负责校验权限和读取数据集的管理器。 */
    public PythonDatasetPreparer(DatasetManager datasets) {
        this.datasets = datasets;
    }

    /** 任务复用沙箱时，每次运行前重新检查已经放入的数据集权限。 */
    public void verify(String datasetId, AgentToolContext identity) {
        var record = datasets.get(datasetId, identity.userId(), identity.conversationId());
        if (!"READY".equals(record.status())) {
            throw new IllegalStateException("数据集尚未发布");
        }
    }

    /**
     * 以授权数据集 ID 命名沙箱输入文件；宿主机路径不会交给模型。
     *
     * @param sandbox 当前任务沙箱
     * @param identity 可信身份
     * @param datasetId 数据集编号
     * @return 数据集编号与沙箱内文件路径
     * @throws IOException 写入沙箱失败
     */
    public Map<String, Object> prepare(PythonSandboxSession sandbox, AgentToolContext identity,
                                       String datasetId) throws IOException {
        try (var dataset = datasets.openReady(datasetId, identity.userId(), identity.conversationId())) {
            DatasetRecord record = dataset.record();
            String fileName = inputFileName(record.datasetId());
            sandbox.writeFile(fileName, dataset.stream());
            Map<String, Object> prepared = new LinkedHashMap<>();
            prepared.put("datasetId", record.datasetId());
            prepared.put("filePath", PythonSandboxSession.INPUT_DIRECTORY + "/" + fileName);
            return prepared;
        }
    }

    /**
     * 沙箱输入文件名；数据集编号即文件名，便于核对文件是否还在。
     *
     * @param datasetId 数据集编号
     * @return 沙箱内的文件名
     */
    public String inputFileName(String datasetId) {
        return datasetId + ".jsonl";
    }
}
