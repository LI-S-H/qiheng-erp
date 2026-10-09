package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.qiheng.erp.ai.chatmodel.AiModelEnabledCondition;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@Conditional(AiModelEnabledCondition.class)
public class AiAgentConfig {
    @Bean("orchestratorAgent")
    public ReactAgent orchestratorAgent(@Qualifier("deepseekProChatModel") ChatModel model) {
        // 数据库历史是唯一上下文源，基础阶段不配置 Saver，避免重复记忆。
        return ReactAgent.builder().name("orchestrator_agent")
                .systemPrompt("你是 ERP 智能助手。用简洁中文回答，可使用 Markdown。当前尚未接入业务查询工具，不能声称已查询或修改 ERP 数据。")
                .model(model).enableLogging(false).build();
    }
}
