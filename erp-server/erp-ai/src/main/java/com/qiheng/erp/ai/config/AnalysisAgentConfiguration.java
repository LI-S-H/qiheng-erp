package com.qiheng.erp.ai.config;

import com.qiheng.erp.ai.analysis.AnalysisAgentService;
import com.qiheng.erp.ai.python.tool.PythonAnalysisTool;
import com.qiheng.erp.ai.python.sandbox.PythonSandboxRuntime;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.interceptor.ToolJsonInterceptor;
import com.qiheng.erp.ai.agent.interceptor.BusinessGraphInterceptor;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.dataset.tool.ListDatasetsTool;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 配置独立数据分析智能体；暂不向主智能体注册。 */
@Configuration(proxyBeanMethods = false)
@Conditional(AiModelEnabledCondition.class)
public class AnalysisAgentConfiguration {
    /** 使用基础 Pro 模型和独立任务工具创建分析入口。 */
    @Bean
    public AnalysisAgentService analysisAgentService(
            @Qualifier("proChatModel") ChatModel model,
            PythonAnalysisTool tools, PythonSandboxRuntime sandboxRuntime, AiAgentTraceMapper traces, ObjectMapper mapper,
            ObjectProvider<SqlQueryTool> sql, ObjectProvider<ReadDatasetTool> datasets,
            ObjectProvider<SystemExceptionMqPublisher> publisher, ObjectProvider<ListDatasetsTool> datasetList,
            AnalysisResultTool results, DatasetManager datasetManager, AnalysisResultStore resultStore,
            BusinessGraphProvider businessGraph) throws IOException {
        String prompt = new ClassPathResource("prompts/analysis-agent.md")
                .getContentAsString(StandardCharsets.UTF_8);
        return new AnalysisAgentService(model, tools, sandboxRuntime, prompt, traces, mapper,
                sql.getIfAvailable(), datasets.getIfAvailable(), new ToolJsonInterceptor(mapper, publisher.getIfAvailable()),
                datasetList.getIfAvailable(), results, datasetManager, resultStore, new BusinessGraphInterceptor(businessGraph));
    }
}
