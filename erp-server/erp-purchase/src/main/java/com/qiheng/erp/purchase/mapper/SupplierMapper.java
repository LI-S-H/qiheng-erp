package com.qiheng.erp.purchase.mapper;

import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 供应商表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
public interface SupplierMapper extends BaseMapper<Supplier> {

    /**
     * 逻辑删除（带乐观锁校验）
     * @param id 供应商ID
     * @param version 版本号
     * @return 受影响行数
     */
    int deleteByIdWithVersion(@Param("id") Long id, @Param("version") Integer version);
}
