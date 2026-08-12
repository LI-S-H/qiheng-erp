package com.qiheng.erp.sales.service.support;

import com.qiheng.erp.sales.service.ISalesOrderService;
import com.qiheng.erp.warehouse.domain.common.enums.SourceType;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBill;
import com.qiheng.erp.warehouse.domain.outbound.entity.OutboundBillItem;
import com.qiheng.erp.warehouse.domain.outbound.port.OutboundSourceWritebackPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 销售订单出库确认后的来源单回写适配器。
 *
 * <p>仓储模块只识别 {@link OutboundSourceWritebackPort}，不直接依赖销售模块服务实现。</p>
 */
@Component
@RequiredArgsConstructor
public class SalesOutboundWritebackPort implements OutboundSourceWritebackPort {

    private final ISalesOrderService salesOrderService;

    /**
     * 支持的出库来源类型（销售订单）。
     */
    @Override
    public boolean supports(String sourceType) {
        return SourceType.SALES_ORDER.name().equals(sourceType);
    }

    /**
     * 出库单已确认后回写销售订单（累加 outbound_qty、推进主表状态）。
     */
    @Override
    public void onOutboundConfirmed(OutboundBill bill, List<OutboundBillItem> items) {
        salesOrderService.handleOutboundConfirmation(bill, items);
    }
}