package com.qiheng.erp.ai.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.context.ContextCompressionFactory;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.report.ReportAgents;
import com.qiheng.erp.ai.report.ReportReadTools;
import com.qiheng.erp.ai.report.ReportTool;
import com.qiheng.erp.ai.report.ReportStore;
import com.qiheng.erp.ai.report.graph.ReportService;
import com.qiheng.erp.ai.report.graph.ReportMaterialPreparer;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.report.graph.ReportConversationReader;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 装配报告撰写、审核及主智能体的报告工具。 */
@Configuration(proxyBeanMethods = false)
@Conditional({AiModelEnabledCondition.class, AiSqlEnabledCondition.class})
public class ReportAgentConfiguration {
    /** 将报告服务作为主智能体可调用的工具。 */
    @Bean
    public ReportTool reportTool(ReportService reports) {
        return new ReportTool(reports);
    }
    /** 装配具备授权数据读取能力的独立报告及审核智能体。 */
    @Bean
    public ReportAgents reportAgents(@Qualifier("proChatModel") ChatModel model, ReadDatasetTool datasets,
                                     AnalysisResultTool results, ObjectMapper mapper, AiAgentTraceMapper traces,
                                     ContextCompressionFactory compression) throws IOException {
        String writer = new ClassPathResource("prompts/report-writer.md").getContentAsString(StandardCharsets.UTF_8);
        String reviewer = new ClassPathResource("prompts/report-reviewer.md").getContentAsString(StandardCharsets.UTF_8);
        return new ReportAgents(model, new ReportReadTools(datasets, results), mapper, writer, reviewer, traces,
                compression);
    }

    /** 创建仅供内部调用的报告图服务。 */
    @Bean
    public ReportService reportService(ReportAgents agents, AiConversationMapper conversations,
                                       DatasetManager datasets, AnalysisResultStore results,
                                       AiMessageMapper messages, ObjectMapper mapper, ReportStore store) {
        return new ReportService(agents, conversations, new ReportMaterialPreparer(datasets, results),
                new ReportConversationReader(messages, mapper), store);
    }
}
