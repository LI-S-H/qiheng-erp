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

    /**
     * 校验产品是否被未完成的业务单据引用（用于停用校验）。
     * 跨表 OR EXISTS 短路：找到任一未完成引用即返回 true。
     *
     * 未完成定义：销售/采购 SUBMITTED/APPROVED/部分出入库；退货 SUBMITTED/APPROVED；出入库 PENDING_CONFIRM；库存 locked_qty > 0。
     * 产品模块不依赖业务模块实体，使用跨表 SQL 避免循环依赖。
     */
    Boolean existsUnfinishedBusinessReferencesByProductIds(@Param("productIds") Collection<String> productIds);

    /**
     * 校验产品是否被任何未软删除的业务记录引用（用于删除校验）。
     * 跨表 OR EXISTS 短路：找到任一引用即返回 true。
     *
     * 产品模块不依赖业务模块实体，使用跨表 SQL 避免循环依赖。
     */
    Boolean existsAnyActiveReferencesByProductIds(@Param("productIds") Collection<String> productIds);
}
