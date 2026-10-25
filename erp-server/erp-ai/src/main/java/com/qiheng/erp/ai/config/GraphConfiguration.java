package com.qiheng.erp.ai.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.properties.AiGraphProperties;
import com.qiheng.erp.ai.config.properties.AiMemoryProperties;
import com.qiheng.erp.ai.graph.GraphTopology;
import com.qiheng.erp.ai.graph.node.DataAccessNode;
import com.qiheng.erp.ai.graph.node.KnowledgeNode;
import com.qiheng.erp.ai.graph.node.NeedCheckNode;
import com.qiheng.erp.ai.graph.node.NeedReviewNode;
import com.qiheng.erp.ai.graph.node.NeedSelectorNode;
import com.qiheng.erp.ai.graph.node.PlannerNode;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.memory.node.NodeMemoryStore;
import com.qiheng.erp.ai.memory.node.PlannerMemoryContext;
import com.qiheng.erp.ai.memory.summary.ContextCompressor;
import com.qiheng.erp.ai.memory.summary.SummaryChatModel;
import com.qiheng.erp.ai.service.VisibleResultFactory;
import com.qiheng.erp.ai.service.data.schema.SchemaProvider;
import com.qiheng.erp.ai.service.knowledge.KnowledgeProvider;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import jakarta.validation.Validator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;

/**
 * 主图的 Spring 装配。
 *
 * <p>本类只负责「把依赖装配成 Bean」；图长什么样（节点、边、State 键策略、编译参数）
 * 全部在 {@link GraphTopology}。改图结构不用碰 Spring 配置类，改执行流程也不用碰这里。</p>
 *
 * @author Li
 * @since 2026-10-08
 */
@Configuration(proxyBeanMethods = false)
@Conditional(AiModelEnabledCondition.class)
public class GraphConfiguration {

    /** 普通节点直接复用摘要算法，沿用现有预算与近期消息保留配置。 */
    @Bean
    public ContextCompressor plannerContextCompressor(
            @Qualifier("deepseekContextSummaryChatModel") ChatModel model,
            AiMemoryProperties memory) {
        SummaryChatModel summary = new SummaryChatModel(model, memory.summaryMaxTokens(),
                memory.summaryMaxHistoryRatio(), memory.summaryProviderMaxOutputTokens(),
                Duration.ofSeconds(memory.summaryTimeoutSeconds()));
        return new ContextCompressor(summary, memory.summaryTriggerTokens(), memory.messagesToKeep());
    }

    /**
     * 主图装配。
     *
     * <p>规划与审阅都用 Flash 模型：一次图任务要打两到四轮模型（拆需求、审阅、
     * 修复重拆），Pro 单轮就 47~97 秒，整轮下来三分钟起。Flash 在同样的需求拆解
     * 与业务审阅上质量够用，代价小一个数量级。</p>
     */
    @Bean
    public CompiledGraph erpGraph(@Qualifier("deepseekFlashChatModel") ChatModel model,
                                  RedisSaver saver,
                                  ObjectMapper mapper,
                                  Validator validator,
                                  NodeMemoryStore nodeMemoryStore,
                                  NodeMemoryRecorder nodeMemoryRecorder,
                                  ContextCompressor plannerContextCompressor,
                                  RunProgressPublisher progressPublisher,
                                  ConversationMessageStore messageStore,
                                  ConversationThreads threads,
                                  VisibleResultFactory visibleResults,
                                  SchemaProvider schemaProvider,
                                  KnowledgeProvider knowledgeProvider,
                                  AiGraphProperties graph) throws Exception {
        String plannerPrompt = new ClassPathResource("prompts/planner.md").getContentAsString(StandardCharsets.UTF_8);
        String reviewPrompt = new ClassPathResource("prompts/need_review.md").getContentAsString(StandardCharsets.UTF_8);
        PlannerMemoryContext context = new PlannerMemoryContext(nodeMemoryStore, plannerContextCompressor, mapper);
        // 上限是代码约束，不进提示词：planner 拿不到这个数，也不会被它干扰拆几条
        PlannerNode planner = new PlannerNode(ChatClient.create(model), plannerPrompt, Clock.systemDefaultZone(),
                nodeMemoryRecorder, mapper, context, progressPublisher);
        NeedCheckNode needCheck = new NeedCheckNode(mapper, validator, graph.maxFormatRetries(),
                graph.maxUserNeeds(), graph.maxDerivedNeeds(), nodeMemoryRecorder, messageStore,
                threads, visibleResults);
        NeedReviewNode needReview = new NeedReviewNode(ChatClient.create(model), reviewPrompt, mapper,
                graph.maxFormatRetries(), graph.maxReviewRounds(), nodeMemoryRecorder, messageStore,
                threads, visibleResults, Clock.systemDefaultZone());
        NeedSelectorNode needSelector = new NeedSelectorNode(mapper);
        DataAccessNode dataAccess = new DataAccessNode(schemaProvider);
        KnowledgeNode knowledge = new KnowledgeNode(knowledgeProvider);
        return GraphTopology.compile(planner, needCheck, needReview, needSelector, dataAccess, knowledge,
                saver, graph.maxFormatRetries());
    }
}
