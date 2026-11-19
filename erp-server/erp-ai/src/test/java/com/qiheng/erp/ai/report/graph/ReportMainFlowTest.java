package com.qiheng.erp.ai.report.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.report.ReportAgents;
import com.qiheng.erp.ai.report.ReportAnalysisStep;
import com.qiheng.erp.ai.report.ReportTool;
import com.qiheng.erp.ai.report.ReportReview;
import com.qiheng.erp.ai.report.ReportWriterOutputConverter;
import com.qiheng.erp.ai.report.ReportProgress;
import com.qiheng.erp.ai.report.ReportStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ReportMainFlowTest {
    @Test
    void savesApprovedBodyBeforeReturningPlaceholder() throws Exception {
        verifyDelivery(false);
    }

    @Test
    void doesNotPublishReportWhenSaveFails() throws Exception {
        verifyDelivery(true);
    }

    private void verifyDelivery(boolean failSave) throws Exception {
        AgentToolContext identity = new AgentToolContext(12, 34);
        AiConversationMapper conversations = mock(AiConversationMapper.class);
        AiMessageMapper messages = mock(AiMessageMapper.class);
        ReportAgents agents = mock(ReportAgents.class);
        when(conversations.selectOwned(34L, 12L)).thenReturn(mock(AiConversation.class));
        when(messages.selectRunMessages(12, 34, "run-1")).thenReturn(List.of(
                new AiMessage().setKind("USER_INPUT").setContent("\"分析库存\"")));
        ReportAgents.Task task = mock(ReportAgents.Task.class);
        var events = new java.util.ArrayList<String>();
        var progress = new ReportProgress("call-1", (name, data) -> events.add(name + ":" + data.get("phase")));
        when(agents.openTask(identity, progress)).thenReturn(task);
        when(task.write(anyString(), isNull())).thenReturn(new ReportWriterOutputConverter.Completed("REPORT", "库存复盘", "库存报告"));
        when(task.review(anyString(), eq("报告标题：库存复盘\n\n库存报告"), isNull()))
                .thenReturn(new ReportReview(8.0, List.of(), false, List.of()));
        var reportStore = mock(ReportStore.class);
        if (failSave) {
            when(reportStore.save(identity, "run-1", "call-1", "库存复盘", "库存报告"))
                    .thenThrow(new IllegalStateException("写库失败"));
        } else {
            when(reportStore.save(identity, "run-1", "call-1", "库存复盘", "库存报告"))
                    .thenReturn(new ReportStore.Reference("rpt_1", "run-1", "call-1", "库存复盘", "[[REPORT:库存复盘]]"));
        }
        var reader = new ReportConversationReader(messages, new ObjectMapper());
        var service = new ReportService(agents, conversations, mock(ReportMaterialPreparer.class), reader, reportStore);
        var tool = new ReportTool(service);
        var context = new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, identity,
                TrustedToolContextInterceptor.CURRENT_RUN_ID, "run-1", ReportProgress.METADATA_KEY, progress));
        var step = new ReportAnalysisStep(1, "了解库存", "查询库存", "共 10 件", List.of("ds_1"), List.of());
        var laterStep = new ReportAnalysisStep(2, "分析库存", "计算库存", "得到分析结果", List.of(), List.of());

        Map<String, Object> result = tool.createReport(List.of(laterStep, step), "只统计本月", context);

        if (failSave) {
            assertThat(result).containsEntry("status", "FAILED").doesNotContainKey("placeholder");
            assertThat(events).contains("report_progress:FAILED").noneMatch(event -> event.startsWith("report_ready"));
        } else {
            assertThat(result).containsEntry("status", "COMPLETED").containsEntry("placeholder", "[[REPORT:库存复盘]]")
                    .doesNotContainKeys("report", "content");
            assertThat(events).containsSubsequence("report_progress:SAVING", "report_ready:null");
        }
        var input = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(task).write(input.capture(), isNull());
        assertThat(input.getValue()).contains("分析库存", "第 1 步", "共 10 件", "ds_1", "只统计本月")
                .containsSubsequence("第 1 步", "共 10 件", "第 2 步", "得到分析结果")
                .doesNotContain("run-1", "conversationId");
        verify(messages).selectRunMessages(12, 34, "run-1");
    }
}
