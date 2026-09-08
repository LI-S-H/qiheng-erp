package com.qiheng.erp.warehouse.service.impl;

import com.qiheng.erp.warehouse.domain.common.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBill;
import com.qiheng.erp.warehouse.domain.stockbill.entity.StockBillItem;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StockBillItemServiceImplTest {

    @Test
    void shouldSetItemCountFromLoadedDetailItems() {
        StockBillItemServiceImpl service = new StockBillItemServiceImpl();

        StockBill bill = new StockBill();
        bill.setId(1L);
        bill.setBillNo("SL-001");
        bill.setBillType(StockBillType.PURCHASE_IN.name());
        bill.setEntryMode(EntryMode.SOURCE_GENERATED.name());
        var detail = service.buildDetails(bill, List.of(detailItem(11L), detailItem(12L)));

        assertThat(detail.getItemCount()).isEqualTo(2);
        assertThat(detail.getItems()).hasSize(2);
    }

    private StockBillItem detailItem(Long id) {
        StockBillItem item = new StockBillItem();
        item.setId(id);
        item.setBillId(1L);
        item.setProductId(id);
        item.setQuantityPrecision(0);
        return item;
    }
}