package com.qiheng.erp.ai.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.skills.ReadSkillTool;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.skills.registry.SkillRegistry;
import com.qiheng.erp.ai.agent.MainAgentService;
import com.qiheng.erp.ai.agent.MessageRecordingHook;
import com.qiheng.erp.ai.agent.context.ContextCompressionFactory;
import com.qiheng.erp.ai.experience.ExperienceInjectionFactory;
import com.qiheng.erp.ai.experience.tool.SearchExperienceToolFactory;
import com.qiheng.erp.ai.agent.interceptor.AnalysisProgressContextInterceptor;
import com.qiheng.erp.ai.agent.interceptor.ReportProgressContextInterceptor;
import com.qiheng.erp.ai.agent.interceptor.ToolRecordingInterceptor;
import com.qiheng.erp.ai.agent.interceptor.ToolJsonInterceptor;
import com.qiheng.erp.ai.clarification.ClarificationService;
import java.util.ArrayList;
import java.util.List;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.agent.interceptor.BusinessGraphInterceptor;
import com.qiheng.erp.ai.clarification.AskUserArgumentsInterceptor;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.security.service.LoginUserService;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.dataset.tool.ListDatasetsTool;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.analysis.AnalysisTool;
import com.qiheng.erp.ai.report.ReportTool;
import com.qiheng.erp.ai.clarification.AskUserTool;
import com.qiheng.erp.ai.skill.SkillIndexInterceptor;
import com.qiheng.erp.ai.skill.tool.ReadSkillFileTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;

/**
 * 绑定现有工具与检查点，供内部应用服务流式调用。
 *
 * @author Li
 * @since 2026-10-27
 */
@Configuration(proxyBeanMethods = false)
@Conditional({AiModelEnabledCondition.class, AiSqlEnabledCondition.class})
public class MainAgentConfiguration {
    @Autowired(required = false)
    private SystemExceptionMqPublisher publisher;
    @Autowired(required = false)
    private ReportTool reportTool;
    @Autowired(required = false)
    private ContextCompressionFactory compression;
    @Autowired(required = false)
    private ExperienceInjectionFactory experienceInjection;
    @Autowired(required = false)
    private SearchExperienceToolFactory experienceTools;

    /**
     * 不挂载技能注册表的兼容入口，供测试和内部构造使用。
     *
     * @param model 主智能体模型
     * @param saver 检查点存储
     * @param sql SQL 查询工具
     * @param dataset 数据集读取工具
     * @param businessGraph 业务图谱
     * @param clarification 追问服务
     * @param datasetList 数据集列表工具
     * @param results 分析结果工具
     * @param analysisTool 分析任务工具
     * @return 主智能体图
     * @throws IOException 提示词读取失败
     */
    public ReactAgent mainAgent(ChatModel model, RedisSaver saver, SqlQueryTool sql, ReadDatasetTool dataset,
                                BusinessGraphProvider businessGraph, ClarificationService clarification,
                                ListDatasetsTool datasetList, AnalysisResultTool results, AnalysisTool analysisTool)
            throws IOException {
        return mainAgent(model, saver, sql, dataset, businessGraph, clarification, datasetList, results,
                analysisTool, null);
    }

    /**
     * 组装主智能体；工具、拦截器与检查点在这里一次性绑定。
     *
     * @param model 主智能体模型
     * @param saver 检查点存储
     * @param sql SQL 查询工具
     * @param dataset 数据集读取工具
     * @param businessGraph 业务图谱
     * @param clarification 追问服务
     * @param datasetList 数据集列表工具
     * @param results 分析结果工具
     * @param analysisTool 分析任务工具
     * @param skills 主智能体技能注册表
     * @return 主智能体图
     * @throws IOException 提示词读取失败
     */
    @Bean
    public ReactAgent mainAgent(@Qualifier("proChatModel") ChatModel model, RedisSaver saver,
                                SqlQueryTool sql, ReadDatasetTool dataset, BusinessGraphProvider businessGraph,
                                ClarificationService clarification, ListDatasetsTool datasetList,
                                AnalysisResultTool results, AnalysisTool analysisTool,
                                @Qualifier("mainSkillRegistry") SkillRegistry skills) throws IOException {
        String prompt = new ClassPathResource("prompts/main-agent.md").getContentAsString(StandardCharsets.UTF_8);
        var tools = new ArrayList<Object>(List.of(sql, dataset, datasetList, results, analysisTool,
                new AskUserTool(clarification)));
        if (skills != null) {
            tools.add(new ReadSkillFileTool(List.of("shared", "main")));
        }
        if (reportTool != null) {
            tools.add(reportTool);
        }
        if (experienceTools != null) {
            tools.add(experienceTools.create("MAIN"));
        }
        var hooks = new ArrayList<Hook>();
        hooks.add(new MessageRecordingHook());
        // 测试直接构造配置时不注入压缩工厂，保持原有行为。
        if (compression != null) {
            hooks.add(compression.create());
        }
        if (experienceInjection != null) {
            hooks.add(experienceInjection.create("MAIN"));
        }
        var builder = ReactAgent.builder().name("main_agent").model(mainModel(model)).systemPrompt(prompt)
                .methodTools(tools.toArray())
                .hooks(hooks)
                .interceptors(new TrustedToolContextInterceptor(), new ToolRecordingInterceptor(),
                        new AnalysisProgressContextInterceptor(), new ReportProgressContextInterceptor(),
                        new ToolJsonInterceptor(new ObjectMapper(), publisher), new AskUserArgumentsInterceptor(new ObjectMapper()), new BusinessGraphInterceptor(businessGraph));
        if (skills != null) {
            builder.interceptors(new SkillIndexInterceptor(skills));
            builder.tools(ReadSkillTool.createReadSkillToolCallback(skills, ""));
        }
        return builder.saver(saver).releaseThread(false).build();
    }

    /** 主智能体串行调用工具，避免同一轮出现多个独立追问。 */
    private ChatModel mainModel(ChatModel model) {
        if (!(model instanceof OpenAiChatModel openAi)) {
            return model;
        }
        var options = ((OpenAiChatOptions) openAi.getDefaultOptions()).copy();
        options.setParallelToolCalls(false);
        return openAi.mutate().defaultOptions(options).build();
    }

    /**
     * 组装主智能体服务。
     *
     * @param mainAgent 主智能体图
     * @param saver 检查点存储
     * @param conversations 会话数据访问
     * @param users 用户数据访问
     * @param locks 会话执行锁
     * @param messages 消息存储
     * @param mapper JSON 序列化组件
     * @param traceMapper 智能体流水写入通道
     * @return 主智能体服务
     */
    @Bean
    public MainAgentService mainAgentService(ReactAgent mainAgent, RedisSaver saver, AiConversationMapper conversations,
                                             LoginUserService users, ConversationExecutionLock locks,
                                             ConversationMessageStore messages, ObjectMapper mapper,
                                             AiAgentTraceMapper traceMapper) {
        return new MainAgentService(mainAgent, saver, conversations, users, locks, messages, mapper, traceMapper);
    }

    /**
     * 组装追问服务。
     *
     * @param conversations 会话数据访问
     * @param users 用户数据访问
     * @param mapper JSON 序列化组件
     * @return 追问服务
     */
    @Bean
    public ClarificationService clarificationService(AiConversationMapper conversations,
            LoginUserService users, ObjectMapper mapper) {
        return new ClarificationService(conversations, users, mapper);
    }
}
