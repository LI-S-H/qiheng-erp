package com.qiheng.erp.ai.report.graph;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class ReportReferencesTest {
    @Test
    void extractsReferencesAndChecksNumbering() {
        var result = ReportReferences.extract("结果是 2%。[1]\n\n## 参考资料\n[1] 损失率，result_001");
        assertThat(result).containsEntry("status", "STRUCTURE_OK");
        assertThat(result.get("references")).isEqualTo(List.of(
                Map.of("number", 1, "id", "result_001", "type", "analysisResult", "name", "损失率")));
    }

    @Test
    void handlesMissingMalformedAndUnsupportedWithoutFalseValidation() {
        assertThat(ReportReferences.extract("普通报告")).containsEntry("status", "NONE");
        assertThat(ReportReferences.extract("数字[1]\n## 参考资料\n[2] 库存 ds_abc"))
                .containsEntry("status", "INVALID");
        assertThat(ReportReferences.extract("数字[1]\n## 参考资料\n[1] 无文件"))
                .containsEntry("status", "INVALID");
        assertThat(ReportReferences.extract("<p>数字[1]</p>\n## 参考资料\n[1] 数据 ds_abc"))
                .containsEntry("status", "UNSUPPORTED");
    }
}
