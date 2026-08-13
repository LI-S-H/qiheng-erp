package com.qiheng.erp.returnorder.service.support;

import com.qiheng.erp.returnorder.service.IReturnOrderService;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBillItem;
import com.qiheng.erp.warehouse.domain.inbound.port.InboundSourceWritebackPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 销售退货入库确认后的来源单回写适配器。
 *
 * <p>仓储模块只识别 {@link InboundSourceWritebackPort}，不直接依赖退货模块的服务实现。</p>
 */
@Component
@RequiredArgsConstructor
public class SalesReturnInboundWritebackPort implements InboundSourceWritebackPort {

    private final IReturnOrderService returnOrderService;

    /**
     * 支持的入库来源类型。
     */
    @Override
    public boolean supports(String sourceType) {
        return SourceType.SALES_RETURN_ORDER.name().equals(sourceType);
    }

    /**
     * 入库单已确认后回写来源单。
     */
    @Override
    public void onInboundConfirmed(InboundBill bill, List<InboundBillItem> items) {
        returnOrderService.handleInboundConfirmation(bill, items);
    }
}
