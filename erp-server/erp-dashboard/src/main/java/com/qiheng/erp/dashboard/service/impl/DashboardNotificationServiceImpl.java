package com.qiheng.erp.dashboard.service.impl;

import com.qiheng.erp.dashboard.domain.enums.DashboardSeverity;
import com.qiheng.erp.dashboard.domain.enums.DashboardTodoStatus;
import com.qiheng.erp.dashboard.domain.vo.DashboardNotificationPopoverVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTodoItemVO;
import com.qiheng.erp.dashboard.service.IDashboardNotificationService;
import com.qiheng.erp.dashboard.service.IDashboardTodoService;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * 工作台顶栏通知铃铛摘要服务实现。
 *
 * <p>复用 {@link IDashboardTodoService} 聚合的待办，截取前 8 条并统计总数，
 * 不再调用重型 {@code DashboardOverviewService}，避免顶栏频繁点击触发
 * trend / orderStage / topProduct / supplierPerformance / stockAlert 等 SQL。</p>
 *
 * <p>排序：先按 {@code sortWeight} 升序，再按 {@code priority} 升序，
 * 再按处理方式（{@code TRACKED} 优先），最后按发生时间倒序。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Service
@RequiredArgsConstructor
public class DashboardNotificationServiceImpl implements IDashboardNotificationService {

    private static final int POPOVER_LIMIT = 8;

    private final IDashboardTodoService todoService;

    /**
     * 加载顶栏通知铃铛摘要
     *
     * @return 前 8 条待办及总数统计
     */
    @Override
    public DashboardNotificationPopoverVO popover() {
        LoginUser user = UserContext.getCurrentUser();
        List<DashboardTodoItemVO> allTodos = todoService.loadTodos(user);
        String pendingCode = DashboardTodoStatus.PENDING.getCode();

        List<DashboardTodoItemVO> pendingTodos = allTodos.stream()
                .filter(t -> pendingCode.equals(t.getStatus()))
                .sorted(Comparator
                        .comparingInt(DashboardTodoItemVO::getSortWeight)
                        .thenComparingInt(t -> priorityWeight(t.getPriority())))
                .toList();

        int pendingCount = pendingTodos.stream()
                .filter(t -> t.getCount() != null)
                .mapToInt(DashboardTodoItemVO::getCount)
                .sum();
        String highCode = DashboardSeverity.HIGH.name();
        int highPriorityCount = pendingTodos.stream()
                .filter(t -> highCode.equals(t.getPriority()))
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

    /** 优先级权重：HIGH=0 < MEDIUM=1 < LOW=2，数值越小越靠前；未知优先级视作 MEDIUM */
    private static int priorityWeight(String priority) {
        if (priority == null) {
            return 1;
        }
        try {
            return switch (DashboardSeverity.valueOf(priority)) {
                case HIGH -> 0;
                case LOW -> 2;
                default -> 1;
            };
        } catch (IllegalArgumentException ex) {
            return 1;
        }
    }
}
