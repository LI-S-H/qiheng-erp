package com.qiheng.erp.warehouse.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.domain.warehousestock.vo.InventoryHealthDistributionVo;
import com.qiheng.erp.warehouse.domain.warehousestock.vo.InventoryRiskPreviewVo;
import com.qiheng.erp.warehouse.domain.warehousestock.vo.RiskStockVo;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * <p>
 * 库存余额表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-07-17
 */
public interface WarehouseStockMapper extends MPJBaseMapper<WarehouseStock> {

    /** 按固定产品顺序锁定同一仓库的库存余额，避免多产品操作互相等待。 */
    List<WarehouseStock> selectByWarehouseAndProductIdsForUpdate(@Param("warehouseId") Long warehouseId,
                                                                  @Param("productIds") List<Long> productIds);

    /** 查询库存风险 SKU，按缺口降序，最多 limit 条。 */
    List<RiskStockVo> selectRiskStocks(@Param("warehouseId") Long warehouseId, @Param("limit") int limit);

    /** 统计库存风险 SKU 数量。 */
    long countRiskStocks();

    /** 按库存健康状态分组统计仓库产品库存记录数。 */
    List<InventoryHealthDistributionVo> selectInventoryHealthDistribution(@Param("warehouseId") Long warehouseId);

    /** 工作台库存状态的轻量风险预览；不复用含采购建议的共享风险查询。 */
    List<InventoryRiskPreviewVo> selectInventoryRiskPreview(@Param("warehouseId") Long warehouseId, @Param("limit") int limit);
}