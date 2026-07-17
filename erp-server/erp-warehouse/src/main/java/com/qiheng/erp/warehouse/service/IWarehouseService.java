package com.qiheng.erp.warehouse.service;

import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.warehouse.domain.dto.WarehousePageDto;
import com.qiheng.erp.warehouse.domain.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.vo.WarehouseVo;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 * 仓库表 服务类
 * </p>
 *
 * @author Li
 * @since 2026-07-16
 */
public interface IWarehouseService extends IService<Warehouse> {

    /**
     * 分页查询仓库
     * @param dto 分页查询参数
     * @return 分页结果集
     */
    PageResult<WarehouseVo> page(WarehousePageDto dto);
}