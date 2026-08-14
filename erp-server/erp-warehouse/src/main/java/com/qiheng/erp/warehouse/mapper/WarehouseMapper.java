package com.qiheng.erp.warehouse.mapper;

import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
     * 查询所有历史仓库编码中的最大合法数字后缀。
     * 逻辑删除记录仍占用唯一编码，因此不能过滤 deleted。
     */
    @Select("""
            SELECT COALESCE(MAX(CAST(SUBSTRING(warehouse_code, #{prefixLength} + 1) AS UNSIGNED)), 0)
            FROM warehouse
            WHERE REGEXP_LIKE(warehouse_code, CONCAT('^', #{prefix}, '[0-9]{', #{width}, '}$'), 'c')
            """)
    Long findMaxWarehouseCodeSequence(@Param("prefix") String prefix,
                                       @Param("prefixLength") int prefixLength,
                                       @Param("width") int width);

    /**
     * 逻辑删除（带乐观锁校验）
     * @param id 仓库ID
     * @param version 版本号
     * @return 受影响行数
     */
    int deleteByIdWithVersion(@Param("id") Long id, @Param("version") Integer version);
}