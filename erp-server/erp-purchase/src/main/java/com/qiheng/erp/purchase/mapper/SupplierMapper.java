package com.qiheng.erp.purchase.mapper;

import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Options;

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
     * 在修改供货关系前锁定所属供应商，统一业务事务与评分校正的行锁顺序。
     *
     * @param supplierId 供应商 ID
     * @return 锁定的供应商 ID；不存在时为 null
     */
    @Select("SELECT id FROM supplier WHERE id = #{supplierId} AND deleted = 0 FOR UPDATE")
    // 清理锁前的 MyBatis 会话缓存，确保锁后重读不会复用等待前的供货产品记录。
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    Long lockByIdForUpdate(@Param("supplierId") Long supplierId);

    /**
     * 查询所有历史供应商编码中的最大合法数字后缀。
     * 逻辑删除记录仍占用唯一编码，因此不能过滤 deleted。
     */
    @Select("""
            SELECT COALESCE(MAX(CAST(SUBSTRING(supplier_code, #{prefixLength} + 1) AS UNSIGNED)), 0)
            FROM supplier
            WHERE REGEXP_LIKE(supplier_code, CONCAT('^', #{prefix}, '[0-9]{', #{width}, '}$'), 'c')
            """)
    Long findMaxSupplierCodeSequence(@Param("prefix") String prefix,
                                      @Param("prefixLength") int prefixLength,
                                      @Param("width") int width);

    /**
     * 逻辑删除（带乐观锁校验）
     * @param id 供应商ID
     * @param version 版本号
     * @return 受影响行数
     */
    int deleteByIdWithVersion(@Param("id") Long id, @Param("version") Integer version);

    /**
     * 累加 supplier.score_basis_amount(分)。
     * SQL 表达式在数据库层做加法,避免读改写竞争;数据库行锁保障并发安全。
     * 用于 PurchaseInboundWritebackPort 入库确认后累加评分样本金额。
     *
     * @param supplierId 供应商 ID
     * @param delta 增减金额(分),可正可负
     * @return 受影响行数(0 表示供应商不存在或已逻辑删除)
     */
    int incrementScoreBasisAmount(@Param("supplierId") Long supplierId,
                                  @Param("delta") Long delta);
}
