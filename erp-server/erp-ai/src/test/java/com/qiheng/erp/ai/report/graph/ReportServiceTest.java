package com.qiheng.erp.ai.report.graph;

import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.report.ReportAgents;
import com.qiheng.erp.ai.report.ReportReadTools;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReportServiceTest {
    /** 使用真实图和智能体装配，模型响应固定，验证节点端到端交接。 */
    @Test
    void runsRealGraphWithBothAgentAdapters() throws Exception {
        var model = mock(ChatModel.class);
        var report = new ChatResponse(List.of(new Generation(new AssistantMessage("{\"decision\":\"REPORT\",\"title\":\"库存报告\",\"content\":\"# 库存报告\\n已完成分析。\"}"))));
        var review = new ChatResponse(List.of(new Generation(new AssistantMessage(
                "{\"score\":8,\"strengths\":[\"清楚\"],\"hasReferenceIssue\":false,\"issues\":[]}"))));
        when(model.call(any(Prompt.class))).thenReturn(report, review);
        var tools = new ReportReadTools(mock(ReadDatasetTool.class), mock(AnalysisResultTool.class));
        var agents = new ReportAgents(model, tools, new ObjectMapper(), "撰写报告", "审核报告");
        var conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var service = new ReportService(agents, conversations, mock(ReportMaterialPreparer.class));
        assertThat(service.run(Map.of("userRequest", "分析库存", "analysisNarrative", "已核对库存"),
                new AgentToolContext(1, 2)))
                .containsEntry("status", "COMPLETED").containsEntry("content", "# 库存报告\n已完成分析。");
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void passesTrustedIdentityToBothAgents() throws Exception {
        var identity = new AgentToolContext(1, 2);
        var conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var agents = mock(ReportAgents.class);
        when(agents.write(anyMap(), eq(identity))).thenReturn("报告正文");
        when(agents.review(anyMap(), eq(identity))).thenReturn(new ReportGraph.Review(8, List.of(), List.of()));
        var service = new ReportService(agents, conversations, mock(ReportMaterialPreparer.class));
        assertThat(service.run(Map.of("userRequest", "分析库存", "analysisNarrative", "查询库存"), identity))
                .containsEntry("status", "COMPLETED");
        verify(agents).write(anyMap(), eq(identity));
        verify(agents).review(anyMap(), eq(identity));
    }

    @Test
    void rejectsUnownedConversation() throws Exception {
        var conversations = mock(AiConversationMapper.class);
        var agents = mock(ReportAgents.class);
        var service = new ReportService(agents, conversations, mock(ReportMaterialPreparer.class));
        assertThatThrownBy(() -> service.run(Map.of(), new AgentToolContext(1, 2)))
                .hasMessageContaining("无权访问");
        verifyNoInteractions(agents);
    }
}
