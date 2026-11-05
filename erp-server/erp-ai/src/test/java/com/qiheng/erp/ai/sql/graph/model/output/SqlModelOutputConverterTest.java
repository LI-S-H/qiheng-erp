package com.qiheng.erp.ai.sql.graph.model.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证官方 Schema 提示与条件分支校验不会互相矛盾。 */
class SqlModelOutputConverterTest {
    private final SqlModelOutputConverter converter = new SqlModelOutputConverter(new ObjectMapper());

    @Test
    void generatesDistinctSchemasAndConvertsBothGenerationBranches() {
        String format = converter.generationFormat();
        assertThat(format).contains("QUERY", "NEED_INFO", "needFullData", "缺少的信息");
        assertThat(converter.readGeneration("{\"decision\":\"QUERY\",\"sql\":\"SELECT id FROM product\",\"needFullData\":false}"))
                .isEqualTo(new SqlQueryOutput("QUERY", "SELECT id FROM product", false));
        assertThat(converter.readGeneration("{\"decision\":\"NEED_INFO\",\"message\":\"请补充仓库\"}"))
                .isEqualTo(new SqlNeedInfoOutput("NEED_INFO", "请补充仓库"));
    }

    @Test
    void rejectsMalformedMissingAndCoercedFields() {
        for (String text : new String[]{"not json", "[]", "{}", "{\"decision\":\"OTHER\"}",
                "{\"decision\":\"NEED_INFO\",\"message\":\"仓库\"}{}",
                "{\"decision\":\"QUERY\",\"sql\":\"SELECT id\"}",
                "{\"decision\":\"QUERY\",\"sql\":\"SELECT id\",\"needFullData\":\"false\"}",
                "{\"decision\":\"NEED_INFO\",\"message\":\" \"}"}) {
            assertThatThrownBy(() -> converter.readGeneration(text)).isInstanceOf(InvalidSqlModelOutput.class);
        }
    }

    @Test
    void inspectionRequiresKnownDecisionAndText() {
        assertThat(converter.inspectionFormat()).contains("decision", "message", "检查决定",
                "重新判断或修正", "不得编造缺失", "仅 PASS 时必须填写");
        assertThat(converter.inspectionFormat()).doesNotContain("\"name\"");
        assertThat(converter.readInspection("{\"decision\":\"PASS\",\"message\":\"结果可用\",\"dataDescription\":{\"content\":\"库存\",\"grain\":\"每条库存\",\"fields\":[{\"index\":0,\"meaning\":\"编号\"}],\"limitations\":\"\"}}"))
                .isEqualTo(new SqlInspectionOutput("PASS", "结果可用", new SqlInspectionDescription("库存", "每条库存", List.of(new SqlInspectionDescription.Field(0, "编号")), "")));
        assertThat(converter.readInspection("{\"decision\":\"FAILED\",\"message\":\"无法完成\"}"))
                .isEqualTo(new SqlInspectionOutput("FAILED", "无法完成", null));
        assertThatThrownBy(() -> converter.readInspection("{\"decision\":\"PASS\",\"message\":\"结果可用\"}"))
                .isInstanceOf(InvalidSqlModelOutput.class);
        assertThatThrownBy(() -> converter.readInspection("{\"decision\":\"NEED_INFO\",\"message\":\"补充信息\"}"))
                .isInstanceOf(InvalidSqlModelOutput.class);
        assertThatThrownBy(() -> converter.readInspection("{\"decision\":\"MAYBE\",\"message\":\"未知\"}"))
                .isInstanceOf(InvalidSqlModelOutput.class);
    }
}
