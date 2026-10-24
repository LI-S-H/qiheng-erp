package com.qiheng.erp.ai.constant;

import java.util.Map;

/**
 * 节点展示文案：进度事件下发使用，前端不硬编码节点名。
 *
 * <p>后续新增节点只在此处补一条映射，前端无需改动。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
public final class NodeLabels {

    private static final Map<String, String> LABELS = Map.of(
            GraphStateKeys.DATA_ACCESS_NODE, "准备数据范围",
            GraphStateKeys.PLANNER_NODE, "正在拆解需求",
            GraphStateKeys.NEED_CHECK_NODE, "校验需求结构",
            GraphStateKeys.NEED_REVIEW_NODE, "审阅需求完整性",
            GraphStateKeys.NEED_SELECTOR_NODE, "选择当前需求",
            GraphStateKeys.HUMAN_FEEDBACK_NODE, "等待补充",
            GraphStateKeys.MEMORY_COMPACT_NODE, "正在压缩历史记忆"
    );

    /**
     * 取节点展示文案。
     *
     * @param nodeName 图节点名，如 planner
     * @return 中文展示文案；未登记的节点原样返回节点名
     */
    public static String of(String nodeName) {
        return LABELS.getOrDefault(nodeName, nodeName);
    }

    private NodeLabels() {
    }
}
