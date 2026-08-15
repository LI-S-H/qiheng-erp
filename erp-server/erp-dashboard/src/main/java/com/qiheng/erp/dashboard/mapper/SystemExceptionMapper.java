package com.qiheng.erp.dashboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiheng.erp.dashboard.domain.entity.SystemException;
import org.apache.ibatis.annotations.Select;

/**
 * <p>
 * 系统异常记录表 Mapper 接口
 * </p>
 *
 * <p>工作台 {@code /dashboard/overview} 的 {@code SYSTEM_EXCEPTION} 待办和后续异常中心复用。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
public interface SystemExceptionMapper extends BaseMapper<SystemException> {

    /**
     * 统计指定状态下的系统异常记录数量
     * @param status 异常状态，例如 PENDING / PROCESSING / RESOLVED
     * @return 命中状态的异常记录条数
     */
    @Select("""
            SELECT COUNT(*)
            FROM system_exception
            WHERE status = #{status}
            """)
    Long countByStatus(String status);
}