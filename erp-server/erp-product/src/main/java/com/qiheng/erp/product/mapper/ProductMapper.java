package com.qiheng.erp.product.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.product.domain.entity.Product;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;

/**
 * <p>
 * 产品表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-06-28
 */
public interface ProductMapper extends MPJBaseMapper<Product> {

    /**
     * 统计仍被未删除供货关系引用的产品数量。
     *
     * 产品模块不依赖采购模块实体，避免产生模块循环依赖。
     */
    Long countActiveSupplierProductReferences(@Param("productIds") Collection<String> productIds);
}
