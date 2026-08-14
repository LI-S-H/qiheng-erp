package com.qiheng.erp.sales.mapper;

import com.qiheng.erp.sales.domain.customer.entity.Customer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 客户表 Mapper 接口
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
public interface CustomerMapper extends BaseMapper<Customer> {

    /**
     * 查询所有历史客户编码中的最大合法数字后缀。
     * 逻辑删除记录仍占用唯一编码，因此不能过滤 deleted。
     */
    @Select("""
            SELECT COALESCE(MAX(CAST(SUBSTRING(customer_code, #{prefixLength} + 1) AS UNSIGNED)), 0)
            FROM customer
            WHERE REGEXP_LIKE(customer_code, CONCAT('^', #{prefix}, '[0-9]{', #{width}, '}$'), 'c')
            """)
    Long findMaxCustomerCodeSequence(@Param("prefix") String prefix,
                                      @Param("prefixLength") int prefixLength,
                                      @Param("width") int width);

    /**
     * 逻辑删除（带乐观锁 version 校验）
     * @param id 客户ID
     * @param version 版本号
     * @return 受影响行数
     */
    int deleteByIdWithVersion(@Param("id") Long id, @Param("version") Integer version);
}