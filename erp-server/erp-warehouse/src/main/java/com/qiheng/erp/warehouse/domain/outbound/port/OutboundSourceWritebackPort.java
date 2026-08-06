package com.qiheng.erp.warehouse.domain.outbound.port;

import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBillItem;
import java.util.List;

/** 出库确认后的来源单回写扩展点。 */
public interface OutboundSourceWritebackPort {
    /** 支持的出库来源类型。 */
    boolean supports(String sourceType);

    /** 出库单已确认后回写来源单。 */
    void onOutboundConfirmed(OutboundBill bill, List<OutboundBillItem> items);
}
