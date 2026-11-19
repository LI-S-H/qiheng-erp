package com.qiheng.erp.ai.report.graph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 保守提取约定格式的 Markdown 引用；其他格式交给审核节点处理。 */
final class ReportReferences {
    private static final Pattern HTML = Pattern.compile("<\\s*/?\\s*[A-Za-z][^>]*>");
    private static final Pattern MARKER = Pattern.compile("(?<!\\\\)\\[(\\d+)\\](?!\\()");
    private static final Pattern ROW = Pattern.compile("^\\s*(?:[-*]\\s*)?\\[(\\d+)\\]\\s*(.+)$");
    private static final Pattern ID = Pattern.compile("\\b(?:ds_[A-Za-z0-9_-]+|result_[A-Za-z0-9_-]+|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})\\b");

    /** 返回解析状态、引用清单和结构问题，不代表引用内容已核实。 */
    static Map<String, Object> extract(String content) {
        if (HTML.matcher(content).find() || content.contains("```") || content.contains("~~~")) {
            return Map.of("status", "UNSUPPORTED", "references", List.of(), "message", "包含 HTML 或代码块，引用结构尚未自动解析。");
        }
        String[] lines = content.split("\\R", -1);
        Set<String> used = new LinkedHashSet<>();
        Map<String, Map<String, Object>> references = new LinkedHashMap<>();
        boolean inReferences = false;
        boolean invalid = false;
        for (String line : lines) {
            if (line.matches("^\\s*#{1,6}\\s*(参考资料|参考文献|引用资料)\\s*#*\\s*$")) {
                inReferences = true;
                continue;
            }
            if (!inReferences) {
                var markers = MARKER.matcher(line);
                while (markers.find()) {
                    used.add(markers.group(1));
                }
                continue;
            }
            if (line.isBlank()) {
                continue;
            }
            var row = ROW.matcher(line);
            if (!row.matches()) {
                invalid = true;
                continue;
            }
            var ids = ID.matcher(row.group(2));
            if (!ids.find()) {
                invalid = true;
                continue;
            }
            String id = ids.group();
            if (ids.find() || references.containsKey(row.group(1))) {
                invalid = true;
                continue;
            }
            int number;
            try {
                number = Integer.parseInt(row.group(1));
            } catch (NumberFormatException error) {
                invalid = true;
                continue;
            }
            references.put(row.group(1), Map.of("number", number,
                    "id", id, "type", id.startsWith("ds_") ? "dataset" : "analysisResult",
                    "name", row.group(2).replace(id, "").replaceAll("^[：:，,\\s]+|[：:，,\\s]+$", "")));
        }
        if (used.isEmpty() && references.isEmpty() && !invalid) {
            return Map.of("status", "NONE", "references", List.of(), "message", "报告未标注引用。");
        }
        if (!used.equals(references.keySet())) {
            invalid = true;
        }
        List<Map<String, Object>> result = new ArrayList<>(references.values());
        return Map.of("status", invalid ? "INVALID" : "STRUCTURE_OK", "references", result,
                "message", invalid ? "引用编号或材料标识不完整，请人工核对。" : "引用结构一致；材料内容仍需审核。");
    }

    private ReportReferences() {}
}
