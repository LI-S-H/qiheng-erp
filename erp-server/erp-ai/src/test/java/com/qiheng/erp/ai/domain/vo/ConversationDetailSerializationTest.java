package com.qiheng.erp.ai.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 全局忽略 null 时，契约要求存在的空节点字段仍需显式返回。 */
class ConversationDetailSerializationTest {

    @Test
    void idleDetailRetainsRequiredNullNodeFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        ConversationDetailVO detail = new ConversationDetailVO("1", "会话", "", "2026-10-16 12:00:00",
                false, null, null, "WAITING_HUMAN");
        var json = mapper.readTree(mapper.writeValueAsString(detail));
        assertThat(json.has("runNode")).isTrue();
        assertThat(json.get("runNode").isNull()).isTrue();
        assertThat(json.has("runLabel")).isTrue();
        assertThat(json.get("runLabel").isNull()).isTrue();
        assertThat(json.get("runStatus").asText()).isEqualTo("WAITING_HUMAN");
        assertThat(json.get("running").asBoolean()).isFalse();
    }
}
