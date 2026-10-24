package com.qiheng.erp.ai.handler;

import com.qiheng.erp.ai.domain.model.IndexDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 索引定义 JSON 往返单测。
 *
 * <p>这条往返是同步程序幂等的前提：读回来的索引定义必须与库里读到的事实逐项相等，
 * 否则每次同步都会判定「索引变了」，反复刷新同一份数据。</p>
 *
 * @author Li
 * @since 2026-10-21
 */
class JsonIndexDefinitionListTypeHandlerTest {

    private final JsonIndexDefinitionListTypeHandler handler = new JsonIndexDefinitionListTypeHandler();

    @Test
    void roundTripKeepsEveryField() throws java.sql.SQLException {
        List<IndexDefinition> original = List.of(
                new IndexDefinition("idx_product_name", false, List.of("product_name")),
                new IndexDefinition("uk_product_code", true, List.of("product_code")));

        String json = write(original);
        List<IndexDefinition> restored = handler.parse(json);

        assertThat(restored).isEqualTo(original);
        assertThat(restored.get(0).name()).isEqualTo("idx_product_name");
        assertThat(restored.get(0).unique()).isFalse();
        assertThat(restored.get(0).columns()).containsExactly("product_name");
        assertThat(restored.get(1).unique()).isTrue();
    }

    @Test
    void emptyJsonBecomesEmptyList() throws java.sql.SQLException {
        assertThat(handler.parse("[]")).isEmpty();
        assertThat(handler.parse(null)).isEmpty();
        assertThat(handler.parse("  ")).isEmpty();
    }

    @Test
    void brokenJsonFailsLoudly() {
        // 索引解析不了说明登记数据坏了，宁可报错也不要静默给模型一份错的索引
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> handler.parse("{not-json"))
                .isInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("索引定义反序列化失败");
    }

    private String write(List<IndexDefinition> definitions) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(definitions);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
