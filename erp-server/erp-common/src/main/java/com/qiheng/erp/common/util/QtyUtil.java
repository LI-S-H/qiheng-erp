package com.qiheng.erp.common.util;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 数量精度转换工具。
 * <p>
 * 数据库中所有数量字段按 100 倍整数存储（如 12.35 存为 1235），
 * 本工具统一提供 存储值 ⇄ 业务小数 的双向转换。
 *
 * @author Li
 * @since 2026-07-25
 */
public final class QtyUtil {

    /** 数量小数位 */
    public static final int SCALE = 2;

    private static final BigDecimal DIVISOR = BigDecimal.valueOf(100);

    private QtyUtil() {
    }

    /**
     * 100 倍存储值转业务小数（1235 → 12.35）
     *
     * @param stored 数据库存储值，null 返回 null
     * @return 业务小数，保留 2 位
     */
    public static BigDecimal toDecimal(Long stored) {
        if (stored == null) {
            return null;
        }
        return BigDecimal.valueOf(stored).divide(DIVISOR, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 100 倍存储值转业务小数（Integer 重载，1235 → 12.35）
     *
     * @param stored 数据库存储值，null 返回 null
     * @return 业务小数，保留 2 位
     */
    public static BigDecimal toDecimal(Integer stored) {
        if (stored == null) {
            return null;
        }
        return BigDecimal.valueOf(stored).divide(DIVISOR, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 100 倍存储值（BigDecimal 形式）转业务小数
     * <p>
     * 适用于 VO 字段已为 BigDecimal、但仍按 100 倍存储的场景。
     *
     * @param stored 数据库存储值，null 返回 null
     * @return 业务小数，保留 2 位
     */
    public static BigDecimal toDecimal(BigDecimal stored) {
        if (stored == null) {
            return null;
        }
        return stored.divide(DIVISOR, SCALE, RoundingMode.HALF_UP);
    }

    /**
     * 业务小数转 100 倍存储值（12.35 → 1235）
     *
     * @param decimal 业务小数，null 返回 null
     * @return 100 倍整数
     */
    public static Long toStored(BigDecimal decimal) {
        if (decimal == null) {
            return null;
        }
        try {
            return decimal.multiply(DIVISOR)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException exception) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "数量或金额超出 BIGINT 可存储范围");
        }
    }

    /**
     * 业务小数转 100 倍存储值（Integer 重载，12.35 → 1235）
     *
     * @param decimal 业务小数，null 返回 null
     * @return 100 倍整数
     */
    public static Integer toStoredInt(BigDecimal decimal) {
        if (decimal == null) {
            return null;
        }
        try {
            return decimal.multiply(DIVISOR)
                    .setScale(0, RoundingMode.HALF_UP)
                    .intValueExact();
        } catch (ArithmeticException exception) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "数值超出 INT 可存储范围");
        }
    }

    /**
     * null 安全转换：null 返回 {@link BigDecimal#ZERO}
     *
     * @param v 任意 BigDecimal，可能为 null
     * @return 原值或 ZERO
     */
    public static BigDecimal defaultZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
