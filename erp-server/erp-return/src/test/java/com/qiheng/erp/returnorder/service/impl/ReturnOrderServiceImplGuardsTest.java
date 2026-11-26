package com.qiheng.erp.returnorder.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.returnorder.domain.dto.ReturnOrderReasonActionRequest;
import com.qiheng.erp.returnorder.domain.dto.ReturnOrderUpdateDto;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 退货单关键业务护栏测试：来源单/仓库不可变，取消必须校验方向权限。
 *
 * @author Li
 * @since 2026-11-26
 */
@ExtendWith(MockitoExtension.class)
class ReturnOrderServiceImplGuardsTest {

    @Mock
    private ReturnOrderMapper returnOrderMapper;

    @Test
    void updateRejectsChangedSourceOrderOrWarehouse() {
        ReturnOrderServiceImpl service = newService();
        ReturnOrder existing = existingOrder();
        when(returnOrderMapper.selectById(1L)).thenReturn(existing);
        ReturnOrderUpdateDto dto = new ReturnOrderUpdateDto();
        dto.setVersion(0);
        dto.setSourceOrderId("11");
        dto.setWarehouseId("20");
        dto.setHandlingType("REFUND");
        dto.setReasonCode("QUALITY_ISSUE");
        dto.setReturnReason("测试");

        try (MockedStatic<StpUtil> permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.checkPermission(anyString())).thenAnswer(invocation -> null);

            assertThatThrownBy(() -> service.update(1L, dto))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("来源单和仓库不能修改");
        }
    }

    @Test
    void updateRejectsChangedWarehouse() {
        ReturnOrderServiceImpl service = newService();
        when(returnOrderMapper.selectById(1L)).thenReturn(existingOrder());
        ReturnOrderUpdateDto dto = new ReturnOrderUpdateDto();
        dto.setVersion(0);
        dto.setSourceOrderId("10");
        dto.setWarehouseId("21");
        dto.setHandlingType("REFUND");
        dto.setReasonCode("QUALITY_ISSUE");
        dto.setReturnReason("测试");

        try (MockedStatic<StpUtil> permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.checkPermission(anyString())).thenAnswer(invocation -> null);

            assertThatThrownBy(() -> service.update(1L, dto))
                    .isInstanceOf(BizException.class)
                    .hasMessageContaining("来源单和仓库不能修改");
        }
    }

    @Test
    void cancelChecksPurchaseManagePermission() {
        ReturnOrderServiceImpl service = newService();
        when(returnOrderMapper.selectById(1L)).thenReturn(existingOrder());
        ReturnOrderReasonActionRequest dto = new ReturnOrderReasonActionRequest();
        dto.setVersion(0);
        dto.setReason("客户取消");

        try (MockedStatic<StpUtil> permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.checkPermission(anyString()))
                    .thenThrow(new IllegalStateException("没有采购管理权限"));

            assertThatThrownBy(() -> service.cancel(1L, dto))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("没有采购管理权限");
            permissions.verify(() -> StpUtil.checkPermission("purchase:manage"));
        }
    }

    @Test
    void cancelChecksSalesManagePermission() {
        ReturnOrderServiceImpl service = newService();
        ReturnOrder existing = existingOrder().setReturnType(ReturnType.SALES_RETURN.name());
        when(returnOrderMapper.selectById(1L)).thenReturn(existing);
        ReturnOrderReasonActionRequest dto = new ReturnOrderReasonActionRequest();
        dto.setVersion(0);
        dto.setReason("客户取消");

        try (MockedStatic<StpUtil> permissions = mockStatic(StpUtil.class)) {
            permissions.when(() -> StpUtil.checkPermission(anyString()))
                    .thenThrow(new IllegalStateException("没有销售管理权限"));

            assertThatThrownBy(() -> service.cancel(1L, dto))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("没有销售管理权限");
            permissions.verify(() -> StpUtil.checkPermission("sales:manage"));
        }
    }

    private ReturnOrderServiceImpl newService() {
        ReturnOrderServiceImpl service = new ReturnOrderServiceImpl();
        ReflectionTestUtils.setField(service, "returnOrderMapper", returnOrderMapper);
        return service;
    }

    private ReturnOrder existingOrder() {
        return new ReturnOrder()
                .setId(1L)
                .setReturnType(ReturnType.PURCHASE_RETURN.name())
                .setSourceOrderId(10L)
                .setWarehouseId(20L)
                .setStatus("DRAFT")
                .setVersion(0);
    }
}