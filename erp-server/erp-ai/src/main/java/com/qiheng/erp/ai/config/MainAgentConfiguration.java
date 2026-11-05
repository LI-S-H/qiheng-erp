package com.qiheng.erp.ai.config;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.qiheng.erp.ai.agent.MainAgentService;
import com.qiheng.erp.ai.agent.MessageRecordingHook;
import com.qiheng.erp.ai.agent.interceptor.ToolRecordingInterceptor;
import com.qiheng.erp.ai.agent.interceptor.ToolJsonInterceptor;
import com.qiheng.erp.ai.clarification.ClarificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.agent.interceptor.BusinessGraphInterceptor;
import com.qiheng.erp.ai.clarification.AskUserArgumentsInterceptor;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import com.qiheng.erp.ai.conversation.ConversationMessageStore;
import com.qiheng.erp.security.service.LoginUserService;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.clarification.AskUserTool;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import com.qiheng.erp.common.mq.SystemExceptionMqPublisher;

/** 绑定现有工具与检查点，供内部应用服务流式调用。 */
@Configuration(proxyBeanMethods = false)
@Conditional({AiModelEnabledCondition.class, AiSqlEnabledCondition.class})
public class MainAgentConfiguration {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SystemExceptionMqPublisher publisher;
    @Bean
    public ReactAgent mainAgent(@Qualifier("proChatModel") ChatModel model, RedisSaver saver,
                                SqlQueryTool sql, ReadDatasetTool dataset, BusinessGraphProvider businessGraph, ClarificationService clarification) throws IOException {
        String prompt = new ClassPathResource("prompts/main-agent.md").getContentAsString(StandardCharsets.UTF_8);
        return ReactAgent.builder().name("main_agent").model(mainModel(model)).systemPrompt(prompt)
                .methodTools(sql, dataset, new AskUserTool(clarification))
                .hooks(new MessageRecordingHook())
                .interceptors(new TrustedToolContextInterceptor(), new ToolRecordingInterceptor(), new ToolJsonInterceptor(new ObjectMapper(), publisher), new AskUserArgumentsInterceptor(new ObjectMapper()), new BusinessGraphInterceptor(businessGraph))
                .saver(saver).releaseThread(false).build();
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

    @Bean
    public MainAgentService mainAgentService(ReactAgent mainAgent, RedisSaver saver, AiConversationMapper conversations,
                                             LoginUserService users, ConversationExecutionLock locks,
                                             ConversationMessageStore messages, ObjectMapper mapper) {
        return new MainAgentService(mainAgent, saver, conversations, users, locks, messages, mapper);
    }

    @Bean
    public ClarificationService clarificationService(AiConversationMapper conversations,
            LoginUserService users, ObjectMapper mapper) {
        return new ClarificationService(conversations, users, mapper);
    }
}
