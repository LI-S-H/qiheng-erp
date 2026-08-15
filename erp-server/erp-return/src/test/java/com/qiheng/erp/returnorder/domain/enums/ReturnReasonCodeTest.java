package com.qiheng.erp.returnorder.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReturnReasonCodeTest {

    @Test
    void normalizesLegacyAndInvalidStoredCodesForApiResponses() {
        assertThat(ReturnReasonCode.normalizeStoredCode("QUALITY")).isEqualTo("QUALITY_ISSUE");
        assertThat(ReturnReasonCode.normalizeStoredCode("DAMAGED")).isEqualTo("DAMAGED");
        assertThat(ReturnReasonCode.normalizeStoredCode("unknown")).isEqualTo("OTHER");
    }
}
