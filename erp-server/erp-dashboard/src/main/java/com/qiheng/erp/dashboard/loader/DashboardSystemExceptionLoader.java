package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.dashboard.domain.exception.entity.SystemException;
import com.qiheng.erp.dashboard.domain.todo.enums.DashboardTodoDetailModel;
import com.qiheng.erp.dashboard.domain.todo.enums.DashboardTodoType;
import com.qiheng.erp.dashboard.domain.exception.enums.SystemExceptionSeverity;
import com.qiheng.erp.dashboard.domain.exception.enums.SystemExceptionStatus;
import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoItemVO;
import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoSystemExceptionDetailVO;
import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoSystemExceptionItemVO;
import com.qiheng.erp.dashboard.mapper.SystemExceptionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 工作台系统异常聚合器。
 *
 * <p>读取 {@code system_exception} 表中 {@code status='PENDING'} 的全部记录，
 * 聚合为一条 {@code SYSTEM_EXCEPTION} 待办（{@code detail.model=SYSTEM_EXCEPTION}，
 * {@code completionMode=TRACKED}），detail 最多展示 5 条具体异常详情。</p>
 *
 * <p>OPENAPI 描述：主卡只显示总数和最高优先级；异常状态由后台重试/补偿/异常中心更新，
 * 工作台只提供"查看详情"，不展示"完成处理"按钮。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class DashboardSystemExceptionLoader {

    private static final int DETAIL_ITEM_LIMIT = 5;

    private final SystemExceptionMapper systemExceptionMapper;

    /**
     * 按权限加载系统异常聚合待办
     *
     * <p>无权限时返回 {@link Optional#empty()}，调用方直接跳过该待办项；
     * 有权限时按当前 {@code PENDING} 异常记录聚合返回。</p>
     *
     * @param canView 是否拥有 dashboard:exception:query 权限
     * @return 可选的系统异常待办；无权限或无异常时返回空
     */
    public Optional<DashboardTodoItemVO> loadIfAllowed(boolean canView) {
        if (!canView) {
            return Optional.empty();
        }
        DashboardTodoItemVO todo = load();
        return todo.getCount() > 0 ? Optional.of(todo) : Optional.empty();
    }

    /**
     * 无条件加载系统异常聚合待办
     *
     * @return 单条 SYSTEM_EXCEPTION 待办；无 PENDING 异常时由 {@link #loadIfAllowed(boolean)} 过滤
     */
    public DashboardTodoItemVO load() {
        // 1. 统计 PENDING 异常数量
        Long countObj = systemExceptionMapper.selectCount(
                new LambdaQueryWrapper<SystemException>()
                        .eq(SystemException::getStatus, SystemExceptionStatus.PENDING.name()));
        int count = countObj == null ? 0 : countObj.intValue();
        // 2. 统计 PENDING 根据发生时间升序，取前 DETAIL_ITEM_LIMIT 条
        List<SystemException> topPending = systemExceptionMapper.selectList(
                new LambdaQueryWrapper<SystemException>()
                        .eq(SystemException::getStatus, SystemExceptionStatus.PENDING.name())
                        .orderByAsc(SystemException::getOccurredAt)
                        .last("LIMIT " + DETAIL_ITEM_LIMIT));
        // 3. 计算最高优先级
        String priority = topPending.stream()
                .map(SystemException::getSeverity)
                .map(this::priorityForSeverity)
                .min(Comparator.comparingInt(DashboardSystemExceptionLoader::priorityRank))
                .orElse("LOW");
        // 4. 组装待办项
        DashboardTodoType type = DashboardTodoType.SYSTEM_EXCEPTION;
        DashboardTodoItemVO todo = new DashboardTodoItemVO();
        todo.setTodoId(type.getTodoId());
        todo.setBusinessType(type.getBusinessType());
        todo.setBusinessLabel(type.getBusinessLabel());
        todo.setTitle(type.getTitle());
        if (count == 0) {
            todo.setDescription("当前无系统异常，AI/MCP、消息队列、第三方回调、定时任务均运行正常。");
        } else {
            todo.setDescription(String.format(
                    "当前有 %d 条系统异常记录需要关注，主要来自 AI/MCP 工具调用、消息队列死信、第三方回调和定时任务失败。",
                    count));
        }
        todo.setCount(count);
        todo.setPriority(priority);
        todo.setSortWeight(type.getSortWeight());
        todo.setCompletionMode(type.getCompletionMode());
        todo.setResolveHint(null);
        DashboardTodoSystemExceptionDetailVO detail = new DashboardTodoSystemExceptionDetailVO();
        detail.setModel(DashboardTodoDetailModel.SYSTEM_EXCEPTION);
        detail.setItems(toDetailItems(topPending));
        todo.setDetail(detail);
        return todo;
    }

    private List<DashboardTodoSystemExceptionItemVO> toDetailItems(List<SystemException> exceptions) {
        List<DashboardTodoSystemExceptionItemVO> result = new ArrayList<>(exceptions.size());
        for (SystemException exception : exceptions) {
            DashboardTodoSystemExceptionItemVO item = new DashboardTodoSystemExceptionItemVO();
            item.setId(exception.getExceptionNo());
            item.setExceptionNo(exception.getExceptionNo());
            item.setSummary(exception.getDetailSummary());
            item.setExceptionType(friendlyType(exception.getExceptionType()));
            item.setSourceModule(friendlyModule(exception.getSourceModule()));
            item.setOccurredAt(exception.getOccurredAt());
            item.setSeverity(priorityForSeverity(exception.getSeverity()));
            result.add(item);
        }
        return result;
    }

    /**
     * 转换系统异常严重级别为待办优先级
     */
    private String priorityForSeverity(String severity) {
        if (SystemExceptionSeverity.HIGH.name().equals(severity)) return SystemExceptionSeverity.HIGH.name();
        if (SystemExceptionSeverity.MEDIUM.name().equals(severity)) return SystemExceptionSeverity.MEDIUM.name();
        if (severity != null) {
            log.warn("未知的系统异常严重级别: {}, 降级为 LOW", severity);
        }
        return SystemExceptionSeverity.LOW.name();
    }

    /**
     * 计算待办优先级的排序权重
     */
    private static int priorityRank(String priority) {
        return switch (priority) {
            case "HIGH" -> 0;
            case "MEDIUM" -> 1;
            default -> 2;
        };
    }

    /**
     * 转换系统异常类型为待办显示名称
     */
    private static String friendlyType(String exceptionType) {
        if (exceptionType == null) {
            return "未知";
        }
        return switch (exceptionType) {
            case "MCP_TOOL_FAILED" -> "MCP超时";
            case "MQ_DEAD_LETTER" -> "死信队列";
            case "EXT_CALLBACK_FAILED" -> "回调超时";
            case "JOB_FAILED" -> "任务失败";
            case "COMPENSATION_FAILED" -> "补偿失败";
            default -> exceptionType;
        };
    }

    /**
     * 转换系统异常来源模块为待办显示名称
     */
    private static String friendlyModule(String sourceModule) {
        if (sourceModule == null) {
            return "未知";
        }
        return switch (sourceModule) {
            case "AI" -> "AI助手";
            case "MQ" -> "消息队列";
            case "LOGISTICS" -> "物流接口";
            case "SCHEDULER" -> "定时任务";
            default -> sourceModule;
        };
    }

}