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

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry("ask_user", "补充信息"),
            Map.entry("prepare_purchase_order_draft", "生成采购草稿"),
            Map.entry("update_purchase_order_draft", "修改采购单"),
            Map.entry("submit_purchase_order", "提交采购审核"),
            Map.entry("cancel_purchase_order", "取消采购单"),
            Map.entry("prepare_sales_order_draft", "生成销售草稿"),
            Map.entry("update_sales_order_draft", "修改销售单"),
            Map.entry("submit_sales_order", "提交销售审核"),
            Map.entry("cancel_sales_order", "取消销售单"),
            Map.entry("query_returnable_items", "查询可退明细"),
            Map.entry("createReport", "生成报告"),
            Map.entry("analyzeData", "子智能体分析"),
            Map.entry("query_sql_data", "查询业务数据"),
            Map.entry("readDataset", "读取数据"),
            Map.entry("listDatasets", "查看数据集"),
            Map.entry("prepareAnalysisData", "准备分析数据"),
            Map.entry("executePython", "执行 Python 分析"),
            Map.entry("listAnalysisResults", "查看分析结果集"),
            Map.entry("readAnalysisResult", "读取分析结果"));

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
