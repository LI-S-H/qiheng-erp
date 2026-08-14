package com.qiheng.erp.purchase.mapper;

import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
}