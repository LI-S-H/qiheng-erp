package com.qiheng.erp.common.util;

import cn.hutool.core.util.StrUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 通用分页/筛选参数校验器。供应商、供货关系等模块共享同一套区间与枚举校验。
 */
public final class ParamValidator {

    /** 评分字段业务值上限,与数据库 CHECK 约束对齐。 */
    public static final int SCORE_MAX = 100;

    private ParamValidator() {
    }

    /**
     * 校验字符串枚举值是否在白名单内。
     *
     * @param value 待校验值,允许 null/blank
     * @param name 字段中文名,用于错误信息
     * @param allowed 合法值集合
     */
    public static void validateEnum(String value, String name, String... allowed) {
        if (StrUtil.isBlank(value)) {
            return;
        }
        Set<String> allowedSet = new HashSet<>(Arrays.asList(allowed));
        if (!allowedSet.contains(value)) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), name + "不合法");
        }
    }

    /**
     * 校验 BigDecimal 区间:两端非负且 min 不大于 max。
     *
     * @param min 区间下限,允许 null
     * @param max 区间上限,允许 null
     * @param name 字段中文名,用于错误信息
     */
    public static void validateNonNegativeRange(BigDecimal min, BigDecimal max, String name) {
        if ((min != null && min.signum() < 0) || (max != null && max.signum() < 0) || (min != null && max != null && min.compareTo(max) > 0)) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), name + "区间不合法");
        }
    }

    /**
     * 校验 0-100 评分区间:非负、上限 100、最多两位小数。
     *
     * @param min 区间下限,允许 null
     * @param max 区间上限,允许 null
     * @param name 字段中文名,用于错误信息
     */
    public static void validateScoreRange(BigDecimal min, BigDecimal max, String name) {
        validateNonNegativeRange(min, max, name);
        if (min != null && (min.compareTo(BigDecimal.valueOf(SCORE_MAX)) > 0 || min.stripTrailingZeros().scale() > QtyUtil.SCALE)) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), name + "必须在 0-" + SCORE_MAX + " 之间且最多两位小数");
        }
        if (max != null && (max.compareTo(BigDecimal.valueOf(SCORE_MAX)) > 0 || max.stripTrailingZeros().scale() > QtyUtil.SCALE)) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), name + "必须在 0-" + SCORE_MAX + " 之间且最多两位小数");
        }
    }
}