package com.qiheng.erp.ai.constant;

import java.util.Map;

/**
 * 工具名称到用户可见文案的统一映射；主智能体与分析智能体共用同一份。
 *
 * <p>此处只保存基础文案。主智能体在工具运行中加"正在"前缀表示进行中，
 * 分析智能体的过程步骤直接使用基础文案。</p>
 *
 * @author Li
 * @since 2026-11-10
 */
public final class AiToolLabels {

    /** 未知工具的兜底文案。 */
    public static final String DEFAULT_LABEL = "处理当前步骤";

    private static final Map<String, String> LABELS = Map.of(
            "analyzeData", "子智能体分析",
            "query_sql_data", "查询业务数据",
            "readDataset", "读取数据",
            "listDatasets", "查看数据集",
            "prepareAnalysisData", "准备分析数据",
            "executePython", "执行 Python 分析",
            "listAnalysisResults", "查看分析结果集",
            "readAnalysisResult", "读取分析结果");

    /** 取工具的基础文案；未知工具使用通用文案。 */
    public static String label(String toolName) {
        return LABELS.getOrDefault(toolName, DEFAULT_LABEL);
    }

    /**
     * 取工具的进行中文案；主智能体的工具状态使用。
     *
     * <p>分析工具直接说明是子智能体在工作，避免用户把它理解成普通查询。</p>
     *
     * @param toolName 工具名
     * @return 进行中文案
     */
    public static String runningLabel(String toolName) {
        if ("analyzeData".equals(toolName)) {
            return "子智能体正在分析";
        }
        return "正在" + label(toolName);
    }

    private AiToolLabels() {
    }
}
