package com.qiheng.erp.sales.mapper;

import com.qiheng.erp.sales.domain.customer.entity.Customer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;

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
     * 逻辑删除（带乐观锁 version 校验）
     * @param id 客户ID
     * @param version 版本号
     * @return 受影响行数
     */
    int deleteByIdWithVersion(@Param("id") Long id, @Param("version") Integer version);
}