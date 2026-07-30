package com.qiheng.erp.purchase.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.purchase.domain.entity.SupplierProduct;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

/**
 * <p>
 * 供应商供货产品表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
public interface SupplierProductMapper extends MPJBaseMapper<SupplierProduct> {

    /**
     * 逻辑删除供货产品（带乐观锁 version 校验）
     * @param id 供货产品ID
     * @param version 期望的版本号
     * @return 受影响行数
     */
    int deleteByIdWithVersion(@Param("id") Long id, @Param("version") Integer version);
}
