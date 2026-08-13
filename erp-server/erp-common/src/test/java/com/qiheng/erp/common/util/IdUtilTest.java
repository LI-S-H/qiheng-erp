package com.qiheng.erp.common.util;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class IdUtilTest {

    @Test
    void toStoredShouldKeepBigintUpperBoundary() {
        assertEquals(Long.MAX_VALUE, QtyUtil.toStored(new BigDecimal("92233720368547758.07")));
    }

    @Test
    void toStoredShouldRejectOverflowInsteadOfTruncating() {
        BizException exception = assertThrows(BizException.class,
                () -> QtyUtil.toStored(new BigDecimal("92233720368547758.08")));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
    }

    @Test
    void toStoredIntShouldRejectOverflowInsteadOfTruncating() {
        BizException exception = assertThrows(BizException.class,
                () -> QtyUtil.toStoredInt(new BigDecimal("21474836.48")));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
    }

    @Test
    void parseRequiredLongIdShouldKeepLargeLongValue() {
        Long id = IdUtil.parseRequiredLongId("9007199254740993", "产品ID");

        assertEquals(9007199254740993L, id);
    }

    @Test
    void parseOptionalLongIdShouldReturnNullForBlankValue() {
        assertNull(IdUtil.parseOptionalLongId("  ", "供应商ID"));
    }

    @Test
    void parseOptionalLongIdShouldRejectNonBlankInvalidValue() {
        BizException exception = assertThrows(BizException.class,
                () -> IdUtil.parseOptionalLongId("supplier-a", "供应商ID"));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
    }

    @Test
    void parseRequiredLongIdShouldRejectOutOfRangeValue() {
        BizException exception = assertThrows(BizException.class,
                () -> IdUtil.parseRequiredLongId("9223372036854775808", "仓库ID"));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
    }

    @Test
    void parseRequiredLongIdsShouldParseAllValuesBeforeReturning() {
        List<Long> ids = IdUtil.parseRequiredLongIds(List.of("1", "2", "3"), "供货关系ID");

        assertEquals(List.of(1L, 2L, 3L), ids);
    }

    @Test
    void parseRequiredLongIdsShouldRejectBlankItem() {
        BizException exception = assertThrows(BizException.class,
                () -> IdUtil.parseRequiredLongIds(List.of("1", " "), "用户ID"));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
    }
}
