package com.qiheng.erp.dashboard.loader;

import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.domain.overview.enums.DashboardAccessState;
import com.qiheng.erp.dashboard.domain.inventory.vo.DashboardInventoryStatusVO;
import com.qiheng.erp.dashboard.domain.overview.vo.DashboardSectionAccessVO;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.warehouse.domain.common.enums.InventoryHealth;
import com.qiheng.erp.warehouse.domain.warehousestock.vo.InventoryHealthDistributionVo;
import com.qiheng.erp.warehouse.domain.warehousestock.vo.InventoryRiskPreviewVo;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DashboardInventoryStatusLoader {
    private static final int PREVIEW_LIMIT = 5;
    private static final int PREVIEW_FETCH_LIMIT = PREVIEW_LIMIT + 1;

    private final DashboardPermissionGuard permissionGuard;
    private final WarehouseStockMapper warehouseStockMapper;

    /**
     * 加载当前登录用户视角的工作台库存状态
     *
     * @param warehouseId 仓库 ID；为 null 时统计全部仓库
     * @return 库存状态分布与风险预览
     */
    public DashboardInventoryStatusVO load(Long warehouseId) {
        return load(warehouseId, UserContext.getCurrentUser());
    }

    /**
     * 加载指定用户视角的工作台库存状态
     *
     * @param warehouseId 仓库 ID；为 null 时统计全部仓库
     * @param user 目标用户，用于裁剪仓储权限
     * @return 库存状态分布与风险预览；无仓储权限时 access 为 DENIED，分布为四种状态的零值占位
     */
    DashboardInventoryStatusVO load(Long warehouseId, LoginUser user) {
        DashboardInventoryStatusVO result = new DashboardInventoryStatusVO();
        // 1. 检查仓储权限
        if (!permissionGuard.canViewWarehouse(user)) {
            result.setAccess(DashboardSectionAccessVO.of(DashboardAccessState.DENIED));
            return result;
        }
        // 2. 加载库存状态分布，统计每个状态的库存记录数
        Map<InventoryHealth, Long> counts = loadDistribution(warehouseId);
        // 3. 统计总库存记录数并填充库存状态分布distribution
        long totalRecordCount = 0;
        for (InventoryHealth health : InventoryHealth.values()) {
            // 从库存状态分布中获取当前状态的库存记录数
            long recordCount = counts.getOrDefault(health, 0L);
            totalRecordCount += recordCount;
            DashboardInventoryStatusVO.Distribution item = new DashboardInventoryStatusVO.Distribution();
            item.setStatus(health.name());
            item.setRecordCount(recordCount);
            result.getDistribution().add(item);
        }
        // 4. 加载库存风险预览(最多 PREVIEW_LIMIT 条)
        List<InventoryRiskPreviewVo> rows = warehouseStockMapper.selectInventoryRiskPreview(warehouseId, PREVIEW_FETCH_LIMIT);
        List<DashboardInventoryStatusVO.Item> items = rows.stream()
                .limit(PREVIEW_LIMIT)
                .map(this::toPreviewItem)
                .toList();
        result.getRiskPreview().setItems(items);
        result.getRiskPreview().setHasMore(rows.size() > PREVIEW_LIMIT);
        result.setAccess(DashboardSectionAccessVO.of(totalRecordCount == 0 ? DashboardAccessState.EMPTY : DashboardAccessState.ALLOWED));
        return result;
    }

    /** 加载库存状态分布。 */
    private Map<InventoryHealth, Long> loadDistribution(Long warehouseId) {
        Map<InventoryHealth, Long> counts = new EnumMap<>(InventoryHealth.class);
        for (InventoryHealth health : InventoryHealth.values()) {
            counts.put(health, 0L);
        }
        // 循环遍历库存状态分布，统计每个状态的库存记录数
        for (InventoryHealthDistributionVo row : warehouseStockMapper.selectInventoryHealthDistribution(warehouseId)) {
            try {
                counts.put(InventoryHealth.valueOf(row.getInventoryHealth()), row.getStockRecordCount() == null ? 0L : row.getStockRecordCount());
            } catch (IllegalArgumentException ignored) {
                // 忽略非法聚合值，保持固定四种状态的合约。
            }
        }
        return counts;
    }

    /** 转换为风险预览项。 */
    private DashboardInventoryStatusVO.Item toPreviewItem(InventoryRiskPreviewVo row) {
        BigDecimal stockQty = decimalOrZero(row.getStockQty());
        BigDecimal availableQty = stockQty.subtract(decimalOrZero(row.getLockedQty()));
        BigDecimal safetyStockQty = decimalOrZero(row.getSafetyStockQty());
        // 转换为风险预览项
        DashboardInventoryStatusVO.Item item = new DashboardInventoryStatusVO.Item();
        item.setStockId(row.getStockId());
        item.setProductCode(row.getProductCode());
        item.setProductName(row.getProductName());
        item.setWarehouseName(row.getWarehouseName());
        item.setUnitName(row.getUnitName());
        item.setQuantityPrecision(row.getQuantityPrecision());
        item.setAvailableQty(availableQty.max(BigDecimal.ZERO));
        item.setSafetyStockQty(safetyStockQty);
        item.setSeverity(resolveRiskHealth(stockQty, availableQty).name());
        return item;
    }

    /** 风险预览项的严重程度：无库存 > 无可用 > 低于安全库存。 */
    private static InventoryHealth resolveRiskHealth(BigDecimal stockQty, BigDecimal availableQty) {
        if (stockQty.signum() == 0) {
            return InventoryHealth.OUT_OF_STOCK;
        }
        if (availableQty.signum() <= 0) {
            return InventoryHealth.NO_AVAILABLE;
        }
        return InventoryHealth.LOW_STOCK;
    }

    private static BigDecimal decimalOrZero(Long value) {
        BigDecimal decimal = QtyUtil.toDecimal(value);
        return decimal == null ? BigDecimal.ZERO : decimal;
    }


}