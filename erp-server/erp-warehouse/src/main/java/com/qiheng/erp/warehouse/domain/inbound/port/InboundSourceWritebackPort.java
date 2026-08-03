package com.qiheng.erp.warehouse.domain.inbound.port;

import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBillItem;

import java.util.List;

/**
 * 入库确认后的来源单回写扩展点。
 *
 * 仓储模块只负责发出已确认的入库事实；具体来源单的数量、状态及后续待确认单，
 * 由拥有该来源业务的模块在同一事务内处理，避免仓储反向依赖采购或销售模块。
 */
public interface InboundSourceWritebackPort {

    /**
     * 是否处理该来源类型。
     *
     * @param sourceType 来源类型
     * @return 是否支持
     */
    boolean supports(String sourceType);

    /**
     * 入库单已确认后回写来源单。
     *
     * @param bill 已确认的入库单
     * @param items 已确认的入库明细
     */
    void onInboundConfirmed(InboundBill bill, List<InboundBillItem> items);
}
