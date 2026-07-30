package com.qiheng.erp.warehouse.service;

import com.qiheng.erp.warehouse.domain.warehousestock.dto.WarehouseStockPageDto;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.baomidou.mybatisplus.extension.service.IService;
import com.qiheng.erp.warehouse.domain.warehousestock.vo.WarehouseStockPageVo;

/**
 * <p>
 * 库存余额表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-07-17
 */
public interface IWarehouseStockService extends IService<WarehouseStock> {
    /**
     * 分页查询库存余额
     * @param dto 分页查询请求
     * @return 库存余额分页结果
     */
    WarehouseStockPageVo page(WarehouseStockPageDto dto);

}
