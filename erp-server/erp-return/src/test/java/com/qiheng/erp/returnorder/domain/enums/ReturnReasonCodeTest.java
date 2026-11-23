package com.qiheng.erp.returnorder.domain.enums;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 退货原因编码只保留对外发布的 7 个枚举值，历史旧码不再由代码兼容。
 *
 * @author Li
 * @since 2026-11-23
 */
class ReturnReasonCodeTest {

    @Test
    void exposesOnlyPublishedCodes() {
        assertThat(ReturnReasonCode.values())
                .extracting(Enum::name)
                .containsExactly("QUALITY_ISSUE", "DAMAGED", "WRONG_ITEM", "QUANTITY_ERROR",
                        "SPEC_MISMATCH", "NO_LONGER_NEEDED", "OTHER");
    }

    @Test
    void rejectsLegacyCodes() {
        // 旧数据已迁移，代码层不再接受 QUALITY / CUSTOMER_RETURN，避免新写入继续产生旧码。
        assertThatThrownBy(() -> ReturnReasonCode.valueOf("QUALITY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReturnReasonCode.valueOf("CUSTOMER_RETURN"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}