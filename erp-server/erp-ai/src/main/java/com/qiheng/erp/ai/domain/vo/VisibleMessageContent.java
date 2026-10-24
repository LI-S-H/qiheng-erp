package com.qiheng.erp.ai.domain.vo;

import java.util.Map;

/** 用户可见流水协议；只承载允许展示的产物，不包含内部 State 或错误堆栈。 */
public record VisibleMessageContent(
        String type,
        Map<String, Object> data
) {
}
