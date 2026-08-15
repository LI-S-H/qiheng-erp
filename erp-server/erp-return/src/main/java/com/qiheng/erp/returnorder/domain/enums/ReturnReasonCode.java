package com.qiheng.erp.returnorder.domain.enums;

import java.util.Set;

/**
 * 退货原因编码。名称与 OpenAPI、前端选项保持一致。
 */
public enum ReturnReasonCode {
    QUALITY_ISSUE,
    DAMAGED,
    WRONG_ITEM,
    QUANTITY_ERROR,
    SPEC_MISMATCH,
    NO_LONGER_NEEDED,
    OTHER;

    private static final Set<String> CODES = Set.of(
            QUALITY_ISSUE.name(), DAMAGED.name(), WRONG_ITEM.name(), QUANTITY_ERROR.name(),
            SPEC_MISMATCH.name(), NO_LONGER_NEEDED.name(), OTHER.name());

    /**
     * 历史库曾写入 QUALITY。对外读取时转换为已发布的规范编码，未知旧值降级为 OTHER，
     * 从而避免一条旧数据阻断整页列表渲染。
     */
    public static String normalizeStoredCode(String value) {
        if ("QUALITY".equals(value)) return QUALITY_ISSUE.name();
        return CODES.contains(value) ? value : OTHER.name();
    }
}
