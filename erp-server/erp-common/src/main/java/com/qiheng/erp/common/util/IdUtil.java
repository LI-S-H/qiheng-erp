package com.qiheng.erp.common.util;

import cn.hutool.core.util.StrUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;

import java.util.Collection;
import java.util.List;

/**
 * Long 类型业务 ID 的请求参数解析工具。
 *
 * <p>前端为避免 JavaScript 精度丢失，以字符串传递 BIGINT ID。该工具统一将格式错误转换为
 * 参数异常，避免 NumberFormatException 直接暴露为服务端异常。</p>
 */
public final class IdUtil {

    private IdUtil() {
    }

    /**
     * 解析必填的 Long 类型业务 ID。
     *
     * @param value 请求中的 ID 字符串
     * @param fieldName 面向调用方的字段名称
     * @return 解析后的 ID
     */
    public static Long parseRequiredLongId(String value, String fieldName) {
        if (StrUtil.isBlank(value)) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), fieldName + "不能为空");
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException exception) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), fieldName + "格式错误，必须为数字字符串");
        }
    }

    /**
     * 解析可选的 Long 类型查询 ID；只有空白值可省略，非空非法值仍应提示参数错误。
     *
     * @param value 请求中的 ID 字符串
     * @param fieldName 面向调用方的字段名称
     * @return 空白值返回 null，否则返回解析后的 ID
     */
    public static Long parseOptionalLongId(String value, String fieldName) {
        return StrUtil.isBlank(value) ? null : parseRequiredLongId(value, fieldName);
    }

    /**
     * 解析必填的 Long 类型业务 ID 列表。
     *
     * @param values 请求中的 ID 字符串集合
     * @param fieldName 面向调用方的字段名称
     * @return 解析后的 ID 列表
     */
    public static List<Long> parseRequiredLongIds(Collection<String> values, String fieldName) {
        if (values == null || values.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), fieldName + "不能为空");
        }
        return values.stream()
                .map(value -> parseRequiredLongId(value, fieldName))
                .toList();
    }
}
