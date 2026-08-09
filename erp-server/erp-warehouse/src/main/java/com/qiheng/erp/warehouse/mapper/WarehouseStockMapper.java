package com.qiheng.erp.warehouse.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
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
}
