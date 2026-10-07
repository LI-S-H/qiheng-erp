package com.qiheng.erp.dashboard.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.qiheng.erp.dashboard.domain.exception.entity.SystemException;
import org.apache.ibatis.annotations.Param;
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

    /**
     * 查询指定日期下 SE 前缀异常编号的最大 5 位序号，供 BillNoGenerator 当 DB 回查函数。
     *
     * @param dayPrefix 完整当天前缀，例：SE20261007
     * @return 当天最大序号，无数据返回 0
     */
    @Select("""
            SELECT COALESCE(MAX(CAST(RIGHT(exception_no, 5) AS UNSIGNED)), 0)
            FROM system_exception
            WHERE exception_no LIKE CONCAT(#{dayPrefix}, '%')
            """)
    long findMaxExceptionNoSequence(@Param("dayPrefix") String dayPrefix);
}