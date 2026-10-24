package com.qiheng.erp.ai.graph.support;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON 载荷截取的契约测试。
 *
 * <p>模型偶尔先写一段分析再给 JSON；截取只负责把载荷捞出来，纯文字仍交给格式修复。</p>
 */
class JsonPayloadTest {

    @Test
    void plainJsonIsReturnedAsIs() {
        assertThat(JsonPayload.unwrap("{\"verdict\":\"OK\"}")).isEqualTo("{\"verdict\":\"OK\"}");
    }

    @Test
    void proseBeforeJsonIsDropped() {
        assertThat(JsonPayload.unwrap("我来审阅一下。\n{\"verdict\":\"OK\"}"))
                .isEqualTo("{\"verdict\":\"OK\"}");
    }

    @Test
    void textWithoutBracesIsReturnedAsIs() {
        assertThat(JsonPayload.unwrap("只有分析没有 JSON")).isEqualTo("只有分析没有 JSON");
    }

    @Test
    void nullBecomesEmpty() {
        assertThat(JsonPayload.unwrap(null)).isEmpty();
    }
}