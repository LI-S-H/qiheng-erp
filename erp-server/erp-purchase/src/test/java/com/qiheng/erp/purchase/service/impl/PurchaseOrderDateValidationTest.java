package com.qiheng.erp.purchase.service.impl;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderCreateDto;
import com.qiheng.erp.purchase.domain.purchaseorder.dto.PurchaseOrderUpdateDto;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

/**
 * 采购单预计到货日期校验：草稿可空，但填写后不得早于今天；提交和审核同样拦截过去日期。
 *
 * @author Li
 * @since 2026-11-26
 */
@ExtendWith(MockitoExtension.class)
class PurchaseOrderDateValidationTest {

    @Mock
    private PurchaseOrderMapper purchaseOrderMapper;

    private PurchaseOrderServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PurchaseOrderServiceImpl();
        ReflectionTestUtils.setField(service, "purchaseOrderMapper", purchaseOrderMapper);
    }

    @Test
    void createRejectsPastArrivalDate() {
        PurchaseOrderCreateDto dto = createDto(LocalDate.now().minusDays(1));

        BizException error = assertThrows(BizException.class, () -> service.createDraft(dto));

        assertEquals("预计到货日期不能早于今天", error.getMessage());
    }

    @Test
    void updateRejectsPastArrivalDate() {
        PurchaseOrderUpdateDto dto = new PurchaseOrderUpdateDto();
        dto.setSupplierId("1");
        dto.setWarehouseId("2");
        dto.setExpectedArrivalDate(LocalDate.now().minusDays(1));
        dto.setVersion(1);

        BizException error = assertThrows(BizException.class, () -> service.update(9L, dto));

        assertEquals("预计到货日期不能早于今天", error.getMessage());
    }

    @Test
    void submitRejectsPastArrivalDate() {
        when(purchaseOrderMapper.selectById(9L)).thenReturn(order(9L, PurchaseOrderStatus.DRAFT,
                LocalDate.now().minusDays(1)));

        BizException error = assertThrows(BizException.class, () -> service.submit(9L, 1));

        assertEquals("预计到货日期不能早于今天", error.getMessage());
    }

    @Test
    void approveRejectsPastArrivalDate() {
        when(purchaseOrderMapper.selectById(9L)).thenReturn(order(9L, PurchaseOrderStatus.SUBMITTED,
                LocalDate.now().minusDays(1)));

        BizException error = assertThrows(BizException.class, () -> service.approve(9L, 1));

        assertEquals("预计到货日期不能早于今天", error.getMessage());
    }

    @Test
    void allowsTodayFutureAndEmptyArrivalDate() {
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "validateExpectedArrivalDate",
                LocalDate.now()));
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "validateExpectedArrivalDate",
                LocalDate.now().plusDays(1)));
        assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(service, "validateExpectedArrivalDate",
                (LocalDate) null));
    }

    private PurchaseOrderCreateDto createDto(LocalDate expectedArrivalDate) {
        PurchaseOrderCreateDto dto = new PurchaseOrderCreateDto();
        dto.setSupplierId("1");
        dto.setWarehouseId("2");
        dto.setExpectedArrivalDate(expectedArrivalDate);
        dto.setItems(List.of());
        return dto;
    }

    private PurchaseOrder order(Long id, PurchaseOrderStatus status, LocalDate expectedArrivalDate) {
        return new PurchaseOrder()
                .setId(id)
                .setStatus(status.name())
                .setVersion(1)
                .setExpectedArrivalDate(expectedArrivalDate);
    }
}