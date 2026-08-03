package com.qiheng.erp.purchase.service.support;

import com.qiheng.erp.purchase.service.IPurchaseOrderService;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBillItem;
import com.qiheng.erp.warehouse.domain.inbound.port.InboundSourceWritebackPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 采购来源的入库确认回写适配器。
 */
@Component
@RequiredArgsConstructor
public class PurchaseInboundWritebackPort implements InboundSourceWritebackPort {

    private final IPurchaseOrderService purchaseOrderService;

    @Override
    public boolean supports(String sourceType) {
        return SourceType.PURCHASE_ORDER.name().equals(sourceType);
    }

    @Override
    public void onInboundConfirmed(InboundBill bill, List<InboundBillItem> items) {
        purchaseOrderService.handleInboundConfirmation(bill, items);
    }
}
