package com.qiheng.erp.warehouse.service.impl;

import com.qiheng.erp.product.domain.entity.Product;
import com.qiheng.erp.warehouse.domain.common.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBill;
import com.qiheng.erp.warehouse.domain.inbound.entity.InboundBillItem;
import com.qiheng.erp.warehouse.domain.inbound.enums.InboundType;
import com.qiheng.erp.warehouse.domain.stockbill.dto.StockBillUpdateDto;
import com.qiheng.erp.warehouse.mapper.InboundBillItemMapper;
import com.qiheng.erp.warehouse.service.IInboundBillItemService;
import com.qiheng.erp.warehouse.service.support.StockBillDraftSupport;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InboundBillServiceImplTest {

    @Test
    void shouldPreservePurchaseUnitPriceWhenReplacingEditedInboundItems() {
        InboundBillServiceImpl service = new InboundBillServiceImpl();
        StockBillDraftSupport draftSupport = mock(StockBillDraftSupport.class);
        IInboundBillItemService itemService = mock(IInboundBillItemService.class);
        InboundBillItemMapper itemMapper = mock(InboundBillItemMapper.class);
        ReflectionTestUtils.setField(service, "stockBillDraftSupport", draftSupport);
        ReflectionTestUtils.setField(service, "inboundBillItemService", itemService);
        ReflectionTestUtils.setField(service, "inboundBillItemMapper", itemMapper);

        InboundBill bill = new InboundBill()
                .setId(1L)
                .setInboundNo("IB-001")
                .setEntryMode(EntryMode.SOURCE_GENERATED.name());
        InboundBillItem original = new InboundBillItem()
                .setId(2L)
                .setSourceItemId(3L)
                .setProductId(4L)
                .setUnitPrice(1234L);
        StockBillUpdateDto edited = new StockBillUpdateDto();
        edited.setSourceItemId("3");
        edited.setProductId("4");
        edited.setPlanQty(new BigDecimal("5"));
        edited.setCurrentQty(new BigDecimal("3"));
        edited.setQualifiedQty(new BigDecimal("3"));
        edited.setDefectiveQty(BigDecimal.ZERO);

        Product product = new Product()
                .setId(4L)
                .setProductCode("P004")
                .setProductName("测试产品")
                .setUnitName("件")
                .setQuantityPrecision(0);
        when(draftSupport.loadProductMap(anyList())).thenReturn(Map.of(4L, product));
        when(itemMapper.sumConfirmedCurrentQtyBySourceItemId(3L)).thenReturn(0L);

        ReflectionTestUtils.invokeMethod(service, "replaceItems", bill, List.of(original),
                List.of(edited), InboundType.PURCHASE_IN);

        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<List<InboundBillItem>> itemsCaptor =
                org.mockito.ArgumentCaptor.forClass(List.class);
        verify(itemService).saveBatch(itemsCaptor.capture());
        InboundBillItem saved = itemsCaptor.getValue().getFirst();
        assertThat(saved.getCurrentQty()).isEqualTo(300L);
        assertThat(saved.getUnitPrice()).isEqualTo(1234L);
        assertThat(saved.getSourceItemId()).isEqualTo(3L);
    }
}
