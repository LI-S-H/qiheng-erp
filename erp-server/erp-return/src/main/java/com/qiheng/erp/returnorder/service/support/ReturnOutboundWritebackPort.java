package com.qiheng.erp.returnorder.service.support;

import com.qiheng.erp.returnorder.service.IReturnOrderService;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBillItem;
import com.qiheng.erp.warehouse.domain.outbound.port.OutboundSourceWritebackPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 采购退货出库确认后的来源单回写适配器。
 *
 * <p>仓储模块只识别 {@link OutboundSourceWritebackPort}，不直接依赖退货模块的服务实现。</p>
 */
@Component
@RequiredArgsConstructor
public class ReturnOutboundWritebackPort implements OutboundSourceWritebackPort {

    private final IReturnOrderService returnOrderService;

    /**
     * 支持的出库来源类型。
     */
    @Override
    public boolean supports(String sourceType) {
        return SourceType.PURCHASE_RETURN_ORDER.name().equals(sourceType);
    }

    /**
     * 出库单已确认后回写来源单。
     */
    @Override
    public void onOutboundConfirmed(OutboundBill bill, List<OutboundBillItem> items) {
        returnOrderService.handleOutboundConfirmation(bill, items);
    }
}
