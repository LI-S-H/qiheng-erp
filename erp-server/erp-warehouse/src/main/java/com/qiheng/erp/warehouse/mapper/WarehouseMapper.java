package com.qiheng.erp.warehouse.mapper;

import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 仓库表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-07-16
 */
public interface WarehouseMapper extends BaseMapper<Warehouse> {

    /**
     * 逻辑删除（带乐观锁校验）
     * @param id 仓库ID
     * @param version 版本号
     * @return 受影响行数
     */
    int deleteByIdWithVersion(@Param("id") Long id, @Param("version") Integer version);
}