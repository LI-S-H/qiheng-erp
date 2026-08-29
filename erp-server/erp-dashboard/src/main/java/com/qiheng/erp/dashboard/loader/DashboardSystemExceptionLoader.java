package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.dashboard.domain.entity.SystemException;
import com.qiheng.erp.dashboard.domain.vo.DashboardTodoEvidenceMetricVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTodoEvidenceVO;
import com.qiheng.erp.dashboard.domain.vo.DashboardTodoItemVO;
import com.qiheng.erp.dashboard.mapper.SystemExceptionMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 工作台系统异常聚合器。
 *
 * <p>读取 {@code system_exception} 表中 {@code status='PENDING'} 的全部记录，
 * 聚合为一条 {@code SYSTEM_EXCEPTION} 待办（{@code sourceMode=PERSISTED}，
 * {@code completionMode=TRACKED}），evidence 最多取 5 条具体异常详情。</p>
 *
 * <p>OPENAPI 描述：主卡只显示总数和最高优先级；异常状态由后台重试/补偿/异常中心更新，
 * 工作台只提供"查看详情"，不展示"完成处理"按钮。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
public class DashboardSystemExceptionLoader {

    private static final int EVIDENCE_LIMIT = 5;
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");

    private final SystemExceptionMapper systemExceptionMapper;

    @Autowired
    public DashboardSystemExceptionLoader(SystemExceptionMapper systemExceptionMapper) {
        this.systemExceptionMapper = systemExceptionMapper;
    }

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
        return Optional.of(load());
    }

    /**
     * 无条件加载系统异常聚合待办
     *
     * @return 单条 SYSTEM_EXCEPTION 待办；无 PENDING 异常时返回 description 为"当前无系统异常"的占位项
     */
    public DashboardTodoItemVO load() {
        List<SystemException> pending = systemExceptionMapper.selectList(
                new LambdaQueryWrapper<SystemException>()
                        .eq(SystemException::getStatus, "PENDING")
                        .orderByAsc(SystemException::getOccurredAt));
        int count = pending.size();
        String priority = pending.stream().anyMatch(e -> "HIGH".equals(e.getSeverity())) ? "HIGH" : "MEDIUM";

        DashboardTodoItemVO todo = new DashboardTodoItemVO();
        todo.setTodoId("todo-system-exceptions");
        todo.setBusinessType("SYSTEM_EXCEPTION");
        todo.setBusinessLabel("系统");
        todo.setTitle("系统异常");
        if (count == 0) {
            todo.setDescription("当前无系统异常，AI/MCP、消息队列、第三方回调、定时任务均运行正常。");
        } else {
            todo.setDescription(String.format(
                    "当前有 %d 条系统异常记录需要关注，主要来自 AI/MCP 工具调用、消息队列死信、第三方回调和定时任务失败。",
                    count));
        }
        todo.setCount(count);
        todo.setPriority(count > 0 ? priority : "LOW");
        todo.setSortWeight(10);
        todo.setSourceMode("PERSISTED");
        todo.setCompletionMode("TRACKED");
        todo.setStatus("PENDING");
        todo.setErrorCode(null);
        todo.setErrorMessage(null);
        todo.setSourceNo("system_exception");
        todo.setOccurredAt(pending.isEmpty() ? null : pending.get(0).getOccurredAt());
        todo.setResolveHint(null);
        todo.setRoute("/dashboard");
        todo.setEvidence(toEvidenceList(pending, EVIDENCE_LIMIT));
        return todo;
    }

    private List<DashboardTodoEvidenceVO> toEvidenceList(List<SystemException> exceptions, int limit) {
        List<DashboardTodoEvidenceVO> result = new ArrayList<>();
        for (int i = 0; i < exceptions.size() && result.size() < limit; i++) {
            SystemException ex = exceptions.get(i);
            DashboardTodoEvidenceVO ev = new DashboardTodoEvidenceVO();
            ev.setItemId(ex.getExceptionNo());
            ev.setPrimaryText(ex.getExceptionNo());
            ev.setSecondaryText(ex.getDetailSummary());
            ev.setMetrics(List.of(
                    metric("类型", friendlyType(ex.getExceptionType()), "HIGH".equals(ex.getSeverity()) ? "risk" : "watch"),
                    metric("来源", friendlyModule(ex.getSourceModule()), "neutral"),
                    metric("时间", formatTime(ex.getOccurredAt()), "neutral")));
            result.add(ev);
        }
        return result;
    }

    private static DashboardTodoEvidenceMetricVO metric(String label, String value, String tone) {
        DashboardTodoEvidenceMetricVO m = new DashboardTodoEvidenceMetricVO();
        m.setLabel(label);
        m.setValue(value);
        m.setTone(tone);
        return m;
    }

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

    private static String formatTime(LocalDateTime when) {
        if (when == null) {
            return "-";
        }
        return when.format(TIME_FORMATTER);
    }
}