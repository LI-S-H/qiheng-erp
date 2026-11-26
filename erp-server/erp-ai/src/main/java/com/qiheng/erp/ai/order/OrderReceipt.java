package com.qiheng.erp.ai.order;

import java.util.Map;

/**
 * 单据确认回执的统一读取入口；各业务只负责提供状态和原始参数。
 *
 * @author Li
 * @since 2026-11-26
 */
public interface OrderReceipt {

    /**
     * 用户处置状态。
     *
     * @return created/updated/edited/rejected/failed/submitted/cancelled
     */
    String status();

    /**
     * 卡片打开时的原始参数；只有修改后确认需要。
     *
     * @return 原始参数；未提供时返回 null
     */
    default Map<String, Object> originalParams() {
        return null;
    }
}