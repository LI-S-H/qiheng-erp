package com.qiheng.erp.dashboard.loader;

import com.qiheng.erp.dashboard.domain.enums.DashboardAccessState;
import com.qiheng.erp.dashboard.domain.vo.DashboardInventoryStatusVO;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.warehousestock.vo.InventoryHealthDistributionVo;
import com.qiheng.erp.warehouse.domain.warehousestock.vo.InventoryRiskPreviewVo;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DashboardInventoryStatusLoaderTest {

    @Test
    void shouldReturnFixedDistributionAndAtMostFiveRiskPreviewItems() {
        DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        WarehouseStockMapper stockMapper = mock(WarehouseStockMapper.class);
        LoginUser user = new LoginUser();
        when(permissionGuard.canViewWarehouse(user)).thenReturn(true);
        when(stockMapper.selectInventoryHealthDistribution(9L)).thenReturn(List.of(
                distribution("NORMAL", 3L), distribution("LOW_STOCK", 2L),
                distribution("NO_AVAILABLE", 1L), distribution("OUT_OF_STOCK", 1L)));
        when(stockMapper.selectInventoryRiskPreview(9L, 6)).thenReturn(List.of(
                risk(1L, 0L, 0L, 400L), risk(2L, 500L, 500L, 800L),
                risk(3L, 1275L, 25L, 1550L), risk(4L, 600L, 0L, 800L),
                risk(5L, 400L, 0L, 800L), risk(6L, 300L, 0L, 800L)));

        DashboardInventoryStatusVO result = new DashboardInventoryStatusLoader(permissionGuard, stockMapper).load(9L, user);

        assertThat(result.getAccess().getState()).isEqualTo(DashboardAccessState.ALLOWED);
        assertThat(result.getDistribution()).extracting(DashboardInventoryStatusVO.Distribution::getStatus)
                .containsExactly("NORMAL", "LOW_STOCK", "NO_AVAILABLE", "OUT_OF_STOCK");
        assertThat(result.getDistribution()).extracting(DashboardInventoryStatusVO.Distribution::getRecordCount)
                .containsExactly(3L, 2L, 1L, 1L);
        assertThat(result.getRiskPreview().getItems()).hasSize(5);
        assertThat(result.getRiskPreview().isHasMore()).isTrue();
        assertThat(result.getRiskPreview().getItems().getFirst().getSeverity()).isEqualTo("OUT_OF_STOCK");
        assertThat(result.getRiskPreview().getItems().get(1).getSeverity()).isEqualTo("NO_AVAILABLE");
        assertThat(result.getRiskPreview().getItems().get(2).getSeverity()).isEqualTo("LOW_STOCK");
        assertThat(result.getRiskPreview().getItems().get(2).getQuantityPrecision()).isEqualTo(2);
        assertThat(result.getRiskPreview().getItems().get(2).getAvailableQty()).isEqualByComparingTo("12.50");
        assertThat(result.getRiskPreview().getItems().get(2).getSafetyStockQty()).isEqualByComparingTo("15.50");
        verify(stockMapper).selectInventoryHealthDistribution(9L);
        verify(stockMapper).selectInventoryRiskPreview(9L, 6);
    }

    @Test
    void shouldKeepEmptyPayloadWhenWarehousePermissionIsDenied() {
        DashboardPermissionGuard permissionGuard = mock(DashboardPermissionGuard.class);
        WarehouseStockMapper stockMapper = mock(WarehouseStockMapper.class);
        LoginUser user = new LoginUser();
        when(permissionGuard.canViewWarehouse(user)).thenReturn(false);

        DashboardInventoryStatusVO result = new DashboardInventoryStatusLoader(permissionGuard, stockMapper).load(null, user);

        // 无权限只由 access 表达；分布与预览都返回空集合，不用零值占位伪装成“有权限但没有数据”。
        assertThat(result.getAccess().getState()).isEqualTo(DashboardAccessState.DENIED);
        assertThat(result.getDistribution()).isEmpty();
        assertThat(result.getRiskPreview().getItems()).isEmpty();
        assertThat(result.getRiskPreview().isHasMore()).isFalse();
        verifyNoInteractions(stockMapper);
    }

    private static InventoryHealthDistributionVo distribution(String health, Long recordCount) {
        InventoryHealthDistributionVo row = new InventoryHealthDistributionVo();
        row.setInventoryHealth(health);
        row.setStockRecordCount(recordCount);
        return row;
    }

    private static InventoryRiskPreviewVo risk(Long stockId, Long stockQty, Long lockedQty, Long safetyStockQty) {
        InventoryRiskPreviewVo row = new InventoryRiskPreviewVo();
        row.setStockId(stockId);
        row.setProductCode("P" + stockId);
        row.setProductName("Product " + stockId);
        row.setWarehouseName("Warehouse");
        row.setUnitName("item");
        row.setQuantityPrecision(stockId.equals(3L) ? 2 : 0);
        row.setStockQty(stockQty);
        row.setLockedQty(lockedQty);
        row.setSafetyStockQty(safetyStockQty);
        return row;
    }
}