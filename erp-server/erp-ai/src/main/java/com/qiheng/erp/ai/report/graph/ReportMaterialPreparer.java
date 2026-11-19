package com.qiheng.erp.ai.report.graph;

import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetUnavailableException;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.python.result.AnalysisResultUnavailableException;
import com.qiheng.erp.ai.report.ReportAnalysisStep;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;

/** 按可信身份整理指定材料的元信息，正文仍由读取工具按需获取。 */
public final class ReportMaterialPreparer {
    private final DatasetManager datasets;
    private final AnalysisResultStore results;

    public ReportMaterialPreparer(DatasetManager datasets, AnalysisResultStore results) {
        this.datasets = datasets;
        this.results = results;
    }

    /** 检查本次交接的引用归属、权限和状态，不读取正文或组装材料目录。 */
    public List<Map<String, String>> unavailableReferences(List<ReportAnalysisStep> steps,
                                                            AgentToolContext identity) throws IOException {
        Set<String> datasetIds = new LinkedHashSet<>();
        Set<String> resultIds = new LinkedHashSet<>();
        for (ReportAnalysisStep step : steps) {
            if (step.datasetIds() != null) {
                datasetIds.addAll(step.datasetIds());
            }
            if (step.resultIds() != null) {
                resultIds.addAll(step.resultIds());
            }
        }
        List<Map<String, String>> unavailable = new ArrayList<>();
        for (String id : datasetIds) {
            try {
                var record = datasets.get(id, identity.userId(), identity.conversationId());
                if (!"READY".equals(record.status())) {
                    throw new DatasetUnavailableException("数据集尚未发布");
                }
            } catch (DatasetUnavailableException error) {
                unavailable.add(Map.of("type", "dataset", "id", id));
            }
        }
        for (String id : resultIds) {
            try {
                // 此处只检查元信息和权限，文件仍需在读取时检查。
                results.find(id, identity);
            } catch (DatasetUnavailableException | AnalysisResultUnavailableException error) {
                unavailable.add(Map.of("type", "analysisResult", "id", id));
            }
        }
        return unavailable;
    }

    /** 仅整理本次指定的材料，不扫描整个会话。 */
    public Map<String, Object> prepare(Map<String, Object> input, AgentToolContext identity) throws IOException {
        List<Map<String, Object>> data = new ArrayList<>();
        for (Object value : (List<?>) input.get("datasetIds")) {
            String id = (String) value;
            try {
                var record = datasets.get(id, identity.userId(), identity.conversationId());
                if (!"READY".equals(record.status())) {
                    throw new DatasetUnavailableException("数据集尚未发布");
                }
                var item = new LinkedHashMap<String, Object>();
                item.put("id", id);
                item.put("status", "AVAILABLE");
                item.put("description", record.description() == null ? "" : record.description());
                item.put("columns", record.columns());
                item.put("rowCount", record.rowCount());
                item.put("completed", record.completed());
                item.put("completenessMessage", record.completenessMessage());
                if (record.dataDescription() != null) {
                    item.put("dataDescription", record.dataDescription());
                }
                data.add(item);
            } catch (DatasetUnavailableException error) {
                data.add(Map.of("id", id, "status", "UNAVAILABLE", "message", "数据集不存在、已过期或无权访问。"));
            }
        }
        List<Map<String, Object>> files = new ArrayList<>();
        for (Object value : (List<?>) input.get("resultIds")) {
            String id = (String) value;
            try {
                var record = results.find(id, identity);
                var item = new LinkedHashMap<String, Object>();
                item.put("id", id);
                item.put("status", "METADATA_AVAILABLE");
                item.put("message", "已获取元信息；文件是否可读需通过读取工具确认。");
                item.put("name", record.getName() == null ? "" : record.getName());
                item.put("description", record.getDescription() == null ? "" : record.getDescription());
                item.put("fileType", record.getFileType() == null ? "" : record.getFileType());
                files.add(item);
            } catch (DatasetUnavailableException | AnalysisResultUnavailableException error) {
                files.add(Map.of("id", id, "status", "UNAVAILABLE", "message", "分析结果或其来源不存在、已过期或无权访问。"));
            }
        }
        return Map.of("datasets", data, "analysisResults", files);
    }
}
