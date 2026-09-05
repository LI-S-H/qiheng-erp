package com.qiheng.erp.dashboard.service.impl;

import com.qiheng.erp.dashboard.domain.vo.DashboardTodoItemVO;
import com.qiheng.erp.dashboard.loader.DashboardSystemExceptionLoader;
import com.qiheng.erp.dashboard.loader.DashboardTodoAggregator;
import com.qiheng.erp.dashboard.permission.DashboardPermissionGuard;
import com.qiheng.erp.dashboard.service.IDashboardTodoService;
import com.qiheng.erp.security.domain.dto.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 工作台待办聚合服务实现。
 *
 * <p>聚合 5 类业务待办 + 1 类系统异常待办，按 sortWeight 升序排序；
 * 系统异常的可见性由 {@code dashboard:exception:query} 决定，业务类待办按
 * 对应 manage / query 权限裁剪。</p>
 *
 * @author Li
 * @since 2026-08-29
 */
@Service
@RequiredArgsConstructor
public class DashboardTodoServiceImpl implements IDashboardTodoService {

    private final DashboardPermissionGuard permissionGuard;
    private final DashboardTodoAggregator todoAggregator;
    private final DashboardSystemExceptionLoader systemExceptionLoader;

    /**
     * 按用户权限聚合业务待办与系统异常待办，按 sortWeight 升序合并
     *
     * @param user 当前登录用户
     * @return 待办 VO 列表
     */
    @Override
    public List<DashboardTodoItemVO> loadTodos(LoginUser user) {
        List<DashboardTodoItemVO> todos = new ArrayList<>();
        todos.addAll(todoAggregator.loadPurchaseTodos(user));
        todos.addAll(todoAggregator.loadSalesTodos(user));
        todos.addAll(todoAggregator.loadPurchaseReturnTodos(user));
        todos.addAll(todoAggregator.loadSalesReturnTodos(user));
        todos.addAll(todoAggregator.loadInboundTodos(user));
        todos.addAll(todoAggregator.loadOutboundTodos(user));
        todos.addAll(todoAggregator.loadStockRiskTodos(user));
        systemExceptionLoader.loadIfAllowed(permissionGuard.canViewSystemException(user))
                .ifPresent(todos::add);
        todos.sort(Comparator.comparingInt(DashboardTodoItemVO::getSortWeight));
        return todos;
    }
}
