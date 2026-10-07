package com.qiheng.erp.common.util;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class ParamValidatorTest {

    @Test
    void validateEnumShouldAcceptBlankValue() {
        assertDoesNotThrow(() -> ParamValidator.validateEnum(null, "评分状态", "NOT_READY", "READY"));
        assertDoesNotThrow(() -> ParamValidator.validateEnum("", "评分状态", "NOT_READY", "READY"));
        assertDoesNotThrow(() -> ParamValidator.validateEnum("   ", "评分状态", "NOT_READY", "READY"));
    }

    @Test
    void validateEnumShouldAcceptValueInAllowed() {
        assertDoesNotThrow(() -> ParamValidator.validateEnum("NOT_READY", "评分状态", "NOT_READY", "READY"));
        assertDoesNotThrow(() -> ParamValidator.validateEnum("READY", "评分状态", "NOT_READY", "READY"));
    }

    @Test
    void validateEnumShouldRejectValueNotInAllowed() {
        BizException exception = assertThrows(BizException.class,
                () -> ParamValidator.validateEnum("INVALID", "评分状态", "NOT_READY", "READY"));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
        assertEquals("评分状态不合法", exception.getMessage());
    }

    @Test
    void validateNonNegativeRangeShouldAcceptNullNull() {
        assertDoesNotThrow(() -> ParamValidator.validateNonNegativeRange(null, null, "评分样本金额"));
    }

    @Test
    void validateNonNegativeRangeShouldAcceptNullMax() {
        assertDoesNotThrow(() -> ParamValidator.validateNonNegativeRange(BigDecimal.ZERO, null, "评分样本金额"));
    }

    @Test
    void validateNonNegativeRangeShouldAcceptNullMin() {
        assertDoesNotThrow(() -> ParamValidator.validateNonNegativeRange(null, BigDecimal.TEN, "评分样本金额"));
    }

    @Test
    void validateNonNegativeRangeShouldAcceptValidRange() {
        assertDoesNotThrow(() -> ParamValidator.validateNonNegativeRange(BigDecimal.ZERO, BigDecimal.TEN, "评分样本金额"));
        assertDoesNotThrow(() -> ParamValidator.validateNonNegativeRange(BigDecimal.valueOf(5), BigDecimal.valueOf(5), "评分样本金额"));
    }

    @Test
    void validateNonNegativeRangeShouldRejectNegativeMin() {
        BizException exception = assertThrows(BizException.class,
                () -> ParamValidator.validateNonNegativeRange(BigDecimal.valueOf(-1), BigDecimal.TEN, "评分样本金额"));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
    }

    @Test
    void validateNonNegativeRangeShouldRejectNegativeMax() {
        BizException exception = assertThrows(BizException.class,
                () -> ParamValidator.validateNonNegativeRange(BigDecimal.ZERO, BigDecimal.valueOf(-1), "评分样本金额"));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
    }

    @Test
    void validateNonNegativeRangeShouldRejectMinGreaterThanMax() {
        BizException exception = assertThrows(BizException.class,
                () -> ParamValidator.validateNonNegativeRange(BigDecimal.valueOf(10), BigDecimal.valueOf(5), "评分样本金额"));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
        assertEquals("评分样本金额区间不合法", exception.getMessage());
    }

    @Test
    void validateScoreRangeShouldAcceptBoundaryValues() {
        assertDoesNotThrow(() -> ParamValidator.validateScoreRange(BigDecimal.ZERO, BigDecimal.valueOf(100), "质量分"));
        assertDoesNotThrow(() -> ParamValidator.validateScoreRange(BigDecimal.valueOf(70.99), BigDecimal.valueOf(99.99), "价格分"));
    }

    @Test
    void validateScoreRangeShouldRejectMinOver100() {
        BizException exception = assertThrows(BizException.class,
                () -> ParamValidator.validateScoreRange(BigDecimal.valueOf(101), null, "质量分"));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
        assertEquals("质量分必须在 0-100 之间且最多两位小数", exception.getMessage());
    }

    @Test
    void validateScoreRangeShouldRejectMaxOver100() {
        BizException exception = assertThrows(BizException.class,
                () -> ParamValidator.validateScoreRange(null, BigDecimal.valueOf(100.01), "质量分"));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
    }

    @Test
    void validateScoreRangeShouldRejectPrecisionExceedTwo() {
        BizException exception = assertThrows(BizException.class,
                () -> ParamValidator.validateScoreRange(BigDecimal.valueOf(70.123), BigDecimal.valueOf(99), "价格分"));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
    }

    @Test
    void validateScoreRangeShouldRejectNegativeMin() {
        BizException exception = assertThrows(BizException.class,
                () -> ParamValidator.validateScoreRange(BigDecimal.valueOf(-1), BigDecimal.valueOf(99), "质量分"));

        assertEquals(ErrorCode.PARAM_ERROR.getCode(), exception.getCode());
    }
}