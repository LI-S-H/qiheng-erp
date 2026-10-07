package com.qiheng.erp.purchase.mapper;

import com.github.yulichang.base.MPJBaseMapper;
import com.qiheng.erp.purchase.domain.supplierproduct.entity.SupplierProduct;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

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
     * 批量查询历史评分归属，包含逻辑删除关系，避免抹去已发生的入库事实。
     *
     * @param ids 非空的供货产品 ID 集合
     * @return 仅包含 ID 与供应商 ID 的供货产品列表
     */
    @Select("""
            <script>
            SELECT id, supplier_id FROM supplier_product
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<SupplierProduct> selectScoringOwners(@Param("ids") java.util.Collection<Long> ids);

    /**
     * 逻辑删除供货产品（带乐观锁 version 校验）
     * @param id 供货产品ID
     * @param version 期望的版本号
     * @return 受影响行数
     */
    int deleteByIdWithVersion(@Param("id") Long id, @Param("version") Integer version);

    /**
     * 累加 supplier_product.score_basis_amount(分)。
     * SQL 表达式在数据库层做加法,避免读改写竞争;数据库行锁保障并发安全。
     * 用于 PurchaseInboundWritebackPort 入库确认后按 SP 维度累加评分样本金额。
     *
     * @param supplierProductId 供货关系 ID
     * @param delta 增减金额(分),可正可负
     * @return 受影响行数(0 表示供货关系不存在或已逻辑删除)
     */
    int incrementScoreBasisAmount(@Param("supplierProductId") Long supplierProductId,
                                  @Param("delta") Long delta);

    /**
     * 仅查 supplier_id(供 PurchaseInboundWritebackPort 反查 supplier 维度累加用)。
     * 用于 source_party_id 缺失时降级:通过 SP 维度反推 supplier_id。
     *
     * @param id 供货产品 ID
     * @return 所属供应商 ID；记录不存在或已删除时返回 null
     */
    @Select("SELECT supplier_id FROM supplier_product WHERE id = #{id} AND deleted = 0")
    Long selectSupplierIdById(@Param("id") Long id);
}
