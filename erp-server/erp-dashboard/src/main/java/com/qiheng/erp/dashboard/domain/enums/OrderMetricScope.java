package com.qiheng.erp.dashboard.domain.enums;

import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import lombok.Getter;

import java.util.List;

/**
 * 工作台经营金额的统一订单口径枚举。
 *
 * <p>顶部经营指标、经营趋势与月快照必须使用同一组状态和审核时间，
 * 避免同一期间的金额出现不一致。</p>
 */
@Getter
public enum OrderMetricScope {

    SALES(List.of(
            SalesOrderStatus.APPROVED.name(),
            SalesOrderStatus.PARTIAL_OUTBOUND.name(),
            SalesOrderStatus.OUTBOUND_DONE.name())),

    PURCHASE(List.of(
            PurchaseOrderStatus.APPROVED.name(),
            PurchaseOrderStatus.PARTIAL_INBOUND.name(),
            PurchaseOrderStatus.INBOUND_DONE.name()));

    private final List<String> statuses;

    OrderMetricScope(List<String> statuses) {
        this.statuses = statuses;
    }

}