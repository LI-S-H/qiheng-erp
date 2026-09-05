package com.qiheng.erp.dashboard.service.support;

import com.qiheng.erp.common.event.dashboard.DashboardTrendMetric;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrderItem;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.mapper.ReturnOrderItemMapper;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardTrendDailyAmountQueryTest {

    @Test
    void shouldAggregateApprovedReturnByApprovalDate() {
        SalesOrderMapper salesOrderMapper = mock(SalesOrderMapper.class);
        PurchaseOrderMapper purchaseOrderMapper = mock(PurchaseOrderMapper.class);
        ReturnOrderMapper returnOrderMapper = mock(ReturnOrderMapper.class);
        ReturnOrderItemMapper returnOrderItemMapper = mock(ReturnOrderItemMapper.class);
        LocalDate approvalDate = LocalDate.of(2026, 9, 1);
        ReturnOrder order = new ReturnOrder().setId(1L)
                .setReturnType(ReturnType.SALES_RETURN.name())
                .setStatus(ReturnStatus.APPROVED.name())
                .setApprovedAt(approvalDate.atTime(10, 0));
        ReturnOrderItem item = new ReturnOrderItem().setReturnOrderId(1L)
                .setApprovedQty(200L).setUnitPrice(1_000L);
        when(returnOrderMapper.selectList(any())).thenReturn(List.of(order));
        when(returnOrderItemMapper.selectList(any())).thenReturn(List.of(item));
        DashboardTrendDailyAmountQuery query = new DashboardTrendDailyAmountQuery(salesOrderMapper,
                purchaseOrderMapper, returnOrderMapper, returnOrderItemMapper);

        Map<LocalDate, Long> amounts = query.query(DashboardTrendMetric.SALES_RETURN, approvalDate, approvalDate);

        assertThat(amounts).containsEntry(approvalDate, 2_000L);
    }
}