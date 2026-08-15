package com.qiheng.erp.dashboard.service.impl;

import com.qiheng.erp.dashboard.domain.vo.DashboardNotificationPopoverVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardOverviewVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTodoItemVO;
import com.qiheng.erp.dashboard.service.IDashboardNotificationService;
import com.qiheng.erp.dashboard.service.IDashboardOverviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 工作台顶栏通知铃铛摘要服务实现。
 *
 * <p>复用 {@link IDashboardOverviewService} 计算的待办，截取前 8 条并统计总数；
 * 待办排序先按 {@code sortWeight} 升序，再按优先级（HIGH > MEDIUM > LOW），最后按发生时间倒序。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Service
public class DashboardNotificationServiceImpl implements IDashboardNotificationService {

    private static final int POPOVER_LIMIT = 8;
    private static final int HIGH_PRIORITY_WEIGHT = 0;
    private static final int MEDIUM_PRIORITY_WEIGHT = 1;
    private static final int LOW_PRIORITY_WEIGHT = 2;

    private final IDashboardOverviewService overviewService;

    @Autowired
    public DashboardNotificationServiceImpl(IDashboardOverviewService overviewService) {
        this.overviewService = overviewService;
    }

    /**
     * 加载顶栏通知铃铛摘要
     * @return 前 8 条待办及总数统计
     */
    @Override
    public DashboardNotificationPopoverVO popover() {
        DashboardOverviewVO overview = overviewService.overview();
        List<DashboardTodoItemVO> allTodos = overview.getTodos();

        List<DashboardTodoItemVO> pendingTodos = allTodos.stream()
                .filter(t -> "PENDING".equals(t.getStatus()))
                .sorted(Comparator
                        .comparingInt(DashboardTodoItemVO::getSortWeight)
                        .thenComparingInt(t -> priorityWeight(t.getPriority())))
                .toList();

        int pendingCount = pendingTodos.stream()
                .filter(t -> t.getCount() != null)
                .mapToInt(DashboardTodoItemVO::getCount)
                .sum();
        int highPriorityCount = pendingTodos.stream()
                .filter(t -> "HIGH".equals(t.getPriority()))
                .filter(t -> t.getCount() != null)
                .mapToInt(DashboardTodoItemVO::getCount)
                .sum();

        List<DashboardTodoItemVO> items = pendingTodos.stream()
                .limit(POPOVER_LIMIT)
                .toList();

        DashboardNotificationPopoverVO vo = new DashboardNotificationPopoverVO();
        vo.setRefreshedAt(LocalDateTime.now());
        vo.setPendingCount(pendingCount);
        vo.setHighPriorityCount(highPriorityCount);
        vo.setHasMore(pendingTodos.size() > POPOVER_LIMIT);
        vo.setItems(items);
        return vo;
    }

    /** 优先级权重：HIGH=0 < MEDIUM=1 < LOW=2，数值越小越靠前 */
    private static int priorityWeight(String priority) {
        if (priority == null) {
            return MEDIUM_PRIORITY_WEIGHT;
        }
        return switch (priority) {
            case "HIGH" -> HIGH_PRIORITY_WEIGHT;
            case "LOW" -> LOW_PRIORITY_WEIGHT;
            default -> MEDIUM_PRIORITY_WEIGHT;
        };
    }
}