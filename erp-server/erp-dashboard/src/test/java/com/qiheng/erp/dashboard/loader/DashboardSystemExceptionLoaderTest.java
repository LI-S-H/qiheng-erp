package com.qiheng.erp.dashboard.loader;

import com.qiheng.erp.dashboard.domain.exception.entity.SystemException;
import com.qiheng.erp.dashboard.domain.todo.enums.DashboardTodoDetailModel;
import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoItemVO;
import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoSystemExceptionDetailVO;
import com.qiheng.erp.dashboard.domain.todo.vo.DashboardTodoSystemExceptionItemVO;
import com.qiheng.erp.dashboard.mapper.SystemExceptionMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardSystemExceptionLoaderTest {

    @Test
    void 应返回独立的系统异常详情模型而非单据字段() {
        SystemExceptionMapper mapper = mock(SystemExceptionMapper.class);
        when(mapper.selectCount(any())).thenReturn(1L);
        when(mapper.selectList(any())).thenReturn(List.of(new SystemException()
                .setExceptionNo("AI-MCP-20260903-001")
                .setExceptionType("MCP_TOOL_FAILED")
                .setSourceModule("AI")
                .setSeverity("HIGH")
                .setDetailSummary("库存预测工具调用超时")
                .setOccurredAt(LocalDateTime.of(2026, 9, 3, 9, 18))));

        DashboardTodoItemVO todo = new DashboardSystemExceptionLoader(mapper).loadIfAllowed(true).orElseThrow();

        assertThat(todo.getCompletionMode()).isEqualTo("TRACKED");
        assertThat(todo.getDetail()).isInstanceOf(DashboardTodoSystemExceptionDetailVO.class);
        DashboardTodoSystemExceptionDetailVO detail = (DashboardTodoSystemExceptionDetailVO) todo.getDetail();
        assertThat(detail.getModel()).isEqualTo(DashboardTodoDetailModel.SYSTEM_EXCEPTION);
        assertThat(detail.getItems()).singleElement().satisfies(item -> {
            assertThat(item.getExceptionNo()).isEqualTo("AI-MCP-20260903-001");
            assertThat(item.getExceptionType()).isEqualTo("MCP超时");
            assertThat(item.getSourceModule()).isEqualTo("AI助手");
            assertThat(item.getSummary()).isEqualTo("库存预测工具调用超时");
        });
    }

    @Test
    void 无待处理异常时不应返回零计数待办() {
        SystemExceptionMapper mapper = mock(SystemExceptionMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());

        assertThat(new DashboardSystemExceptionLoader(mapper).loadIfAllowed(true)).isEmpty();
        assertThat(new DashboardSystemExceptionLoader(mapper).loadIfAllowed(false)).isEmpty();
    }
}