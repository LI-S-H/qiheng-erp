package com.qiheng.erp.ai.analysis;

import java.util.List;

/**
 * 业务完成情况由模型判断；执行失败由服务端设置 FAILED。
 *
 * @author Li
 * @since 2026-11-10
 */
public record AnalysisResult(String completion, String analysisTaskId, String summary,
                             List<Dataset> datasets, List<Dataset> providedDatasets,
                             List<File> analysisResults, String errorCode) {
    /** 本次工具实际取得或读取的数据集。 */
    public record Dataset(String datasetId, String description, int rowCount, boolean completed,
                          String completenessMessage) {
    }

    /** 服务端已保存的本次分析文件或文本。 */
    public record File(String resultId, String name, String resultType, String fileType, Long size) {
    }
}
