package com.qiheng.erp.dashboard.service;

import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoItemVO;
import com.qiheng.erp.security.domain.dto.LoginUser;

import java.util.List;

/**
 * 工作台待办聚合服务。
 *
 * <p>顶栏铃铛与工作台首屏待办卡共用本服务，避免通知接口复用重型
 * {@link IDashboardOverviewService}。按当前用户的业务 manage/query 权限
 * 与 {@code dashboard:exception:query} 独立裁剪各类待办。</p>
 *
 * @author Li
 * @since 2026-08-29
 */
public interface IDashboardTodoService {

    /**
     * 按用户权限聚合业务待办与系统异常待办，按 sortWeight 升序合并
     *
     * @param user 当前登录用户
     * @return 待办 VO 列表，业务待办与系统异常待办合并后升序排序
     */
    List<DashboardTodoItemVO> loadTodos(LoginUser user);
}