package com.qiheng.erp.returnorder.service.impl;

import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrderItem;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceItemApprovalSummary;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceOrderApprovalSummary;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.mapper.ReturnOrderItemMapper;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 验证退货审批事实只累计有效状态，取消单不会影响原订单退货概览或经营金额。
 */
class ReturnOrderApprovalSummaryProviderImplTest {

    @Test
    void shouldAggregateMultipleEffectiveReturnOrdersAndExcludeCancelledOrder() {
        ReturnOrderMapper returnOrderMapper = mock(ReturnOrderMapper.class);
        ReturnOrderItemMapper returnOrderItemMapper = mock(ReturnOrderItemMapper.class);
        ReturnOrderApprovalSummaryProviderImpl provider = new ReturnOrderApprovalSummaryProviderImpl(
                returnOrderMapper, returnOrderItemMapper);

        when(returnOrderMapper.selectList(any())).thenReturn(List.of(
                order(1L, ReturnStatus.APPROVED),
                order(2L, ReturnStatus.PARTIAL_EXECUTED),
                order(3L, ReturnStatus.CANCELLED)
        ));
        when(returnOrderItemMapper.selectList(any())).thenReturn(List.of(
                item(1L, 101L, 200L, 500L),
                item(2L, 101L, 100L, 500L),
                item(2L, 102L, 50L, 1_200L)
        ));

        Map<Long, ReturnSourceOrderApprovalSummary> summaries = provider.summarize(
                ReturnType.SALES_RETURN, List.of(9001L));

        assertThat(summaries).containsOnlyKeys(9001L);
        ReturnSourceOrderApprovalSummary summary = summaries.get(9001L);
        assertThat(summary.returnOrderCount()).isEqualTo(3);
        assertThat(summary.effectiveReturnOrderCount()).isEqualTo(2);
        assertThat(summary.approvedReturnAmount()).isEqualTo(2_100L);
        assertItem(summary.itemSummaries().get(101L), 300L, 1_500L);
        assertItem(summary.itemSummaries().get(102L), 50L, 600L);
    }

    private ReturnOrder order(Long id, ReturnStatus status) {
        return new ReturnOrder()
                .setId(id)
                .setSourceOrderId(9001L)
                .setReturnType(ReturnType.SALES_RETURN.name())
                .setStatus(status.name());
    }

    private ReturnOrderItem item(Long returnOrderId, Long sourceOrderItemId, Long approvedQty, Long unitPrice) {
        return new ReturnOrderItem()
                .setReturnOrderId(returnOrderId)
                .setSourceOrderItemId(sourceOrderItemId)
                .setApprovedQty(approvedQty)
                .setUnitPrice(unitPrice);
    }

    private void assertItem(ReturnSourceItemApprovalSummary summary, long expectedQty, long expectedAmount) {
        assertThat(summary.approvedReturnQty()).isEqualTo(expectedQty);
        assertThat(summary.approvedReturnAmount()).isEqualTo(expectedAmount);
    }
}