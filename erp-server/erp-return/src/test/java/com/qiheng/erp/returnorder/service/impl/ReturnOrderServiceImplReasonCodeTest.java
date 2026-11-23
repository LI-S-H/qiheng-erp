package com.qiheng.erp.returnorder.service.impl;

import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.vo.ReturnOrderVo;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 退货单原因码读取不再做兼容转换：库里存什么就返回什么，迁移由数据脚本负责。
 *
 * @author Li
 * @since 2026-11-23
 */
class ReturnOrderServiceImplReasonCodeTest {

    private final ReturnOrderServiceImpl service = new ReturnOrderServiceImpl();

    @Test
    void returnsPublishedReasonCodeAsStored() {
        ReturnOrderVo vo = toVo("DAMAGED");

        assertThat(vo.getReasonCode()).isEqualTo("DAMAGED");
    }

    @Test
    void keepsAllPublishedCodesUnchanged() {
        for (String code : new String[]{"QUALITY_ISSUE", "DAMAGED", "WRONG_ITEM", "QUANTITY_ERROR",
                "SPEC_MISMATCH", "NO_LONGER_NEEDED", "OTHER"}) {
            assertThat(toVo(code).getReasonCode()).isEqualTo(code);
        }
    }

    @Test
    void noLongerSilentlyRewritesUnknownStoredValue() {
        // 兼容逻辑删除后，读取层不再把未知值改成 OTHER；旧码应当由数据迁移清零。
        assertThat(toVo("CUSTOMER_RETURN").getReasonCode()).isEqualTo("CUSTOMER_RETURN");
    }

    /** 用最小字段构造实体并调用主表转 VO。 */
    private ReturnOrderVo toVo(String reasonCode) {
        ReturnOrder entity = new ReturnOrder()
                .setId(1L)
                .setReasonCode(reasonCode)
                .setTotalAmount(0L);
        return (ReturnOrderVo) ReflectionTestUtils.invokeMethod(service, "toVo", entity);
    }
}