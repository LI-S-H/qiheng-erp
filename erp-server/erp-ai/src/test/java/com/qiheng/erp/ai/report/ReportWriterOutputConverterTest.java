package com.qiheng.erp.ai.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportWriterOutputConverterTest {
    private final ReportWriterOutputConverter converter = new ReportWriterOutputConverter(new ObjectMapper());

    @Test
    void parsesBothBranchesAndPreservesReportText() {
        assertThat(converter.parse("{\"decision\":\"REPORT\",\"title\":\"库存报告\",\"content\":\"结论：10 件\\n[1] 资料\"}"))
                .isInstanceOf(ReportWriterOutputConverter.Completed.class);
        assertThat(converter.parse("{\"decision\":\"REFERENCE_UNAVAILABLE\",\"message\":\"无法读取\","
                + "\"unavailableReferences\":[{\"type\":\"dataset\",\"id\":\"ds_1\"}]}"))
                .isInstanceOf(ReportWriterOutputConverter.Unavailable.class);
        assertThat(converter.outputFormat()).contains("REPORT", "REFERENCE_UNAVAILABLE", "content");
    }

    @Test
    void rejectsWrongBranchFieldsAndTrailingTokens() {
        assertThatThrownBy(() -> converter.parse("{\"decision\":\"REPORT\"}"))
                .hasMessageContaining("title");
        assertThatThrownBy(() -> converter.parse("{\"decision\":\"REPORT\",\"title\":\"标题\"}"))
                .hasMessageContaining("content");
        assertThatThrownBy(() -> converter.parse("{\"decision\":\"REFERENCE_UNAVAILABLE\",\"message\":\"错误\","
                + "\"unavailableReferences\":[{\"type\":\"other\",\"id\":\"x\"}]}"))
                .hasMessageContaining("类型和 ID");
        assertThatThrownBy(() -> converter.parse("{\"decision\":\"REPORT\",\"title\":\"库存报告\",\"content\":\"ok\"} trailing"))
                .isInstanceOf(ReportWriterOutputConverter.InvalidOutput.class);
    }
}
