package com.qiheng.erp.ai.dev;

import com.qiheng.erp.ai.config.GraphConfiguration;
import com.qiheng.erp.ai.config.RedisSaverConfiguration;
import com.qiheng.erp.ai.controller.AiAssistantController;
import com.qiheng.erp.ai.controller.GraphController;
import com.qiheng.erp.ai.memory.node.NodeMemoryStore;
import com.qiheng.erp.ai.service.VisibleMessageCodec;
import com.qiheng.erp.ai.service.VisibleResultFactory;
import com.qiheng.erp.ai.graph.support.ConversationExecutionLock;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.service.impl.GraphServiceImpl;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.graph.runtime.RunCoordinator;
import com.qiheng.erp.ai.graph.runtime.RunFailureHandler;
import com.qiheng.erp.ai.graph.runtime.RunResumePreparer;
import com.qiheng.erp.ai.graph.runtime.RunStartPreparer;
import com.qiheng.erp.ai.graph.runtime.RunStateResolver;
import com.qiheng.erp.ai.service.impl.AiAssistantServiceImpl;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import com.qiheng.erp.common.config.JacksonConfig;
import com.qiheng.erp.common.config.MybatisPlusConfig;
import com.qiheng.erp.security.config.SaTokenConfig;
import com.qiheng.erp.security.interceptor.SaTokenExceptionHandler;
import com.qiheng.erp.security.interceptor.StpInterfaceImpl;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 使用隔离 MySQL、Redis 和受控模型验证生产消息流水，不读取生产模型配置。
 *
 * <p>只存在于 test classpath，数据库地址固定为本机测试端口；HTTP、权限、Mapper、
 * 事务、节点记忆与官方检查点均采用真实实现。</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "org.redisson.spring.starter.RedissonAutoConfigurationV2"
})
@MapperScan("com.qiheng.erp.ai.mapper")
@Import({GraphConfiguration.class, RedisSaverConfiguration.class,
        GraphController.class, AiAssistantController.class,
        GraphServiceImpl.class, AiAssistantServiceImpl.class,
        ConversationExecutionLock.class, VisibleMessageCodec.class, VisibleResultFactory.class,
        ConversationMessageStore.class, NodeMemoryRecorder.class, ConversationThreads.class,
        RunStartPreparer.class, RunResumePreparer.class, RunFailureHandler.class,
        RunCoordinator.class, RunStateResolver.class,
        RunProgressPublisher.class, NodeMemoryStore.class,
        SaTokenConfig.class, SaTokenExceptionHandler.class, StpInterfaceImpl.class,
        JacksonConfig.class, MybatisPlusConfig.class,
        GraphUiIntegrationApplication.TestEndpoints.class,
        GraphUiIntegrationApplication.TestExceptionAdvice.class})
public class GraphUiDatabaseIntegrationApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(GraphUiDatabaseIntegrationApplication.class);
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.config.location", "optional:classpath:/graph-ui-database-test.properties");
        properties.put("spring.application.name", "graph-ui-database-integration-test");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "18082");
        properties.put("spring.datasource.url",
                "jdbc:mysql://127.0.0.1:13316/erp?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false");
        properties.put("spring.datasource.username", "root");
        // 仅对应独立测试容器的公开测试口令，不是业务环境凭据。
        properties.put("spring.datasource.password", "ai-integration-only");
        properties.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        // 联调只扫描 AI 注解 Mapper，避免 classpath 同时含旧模块 JAR 与编译目录时重复加载业务 XML。
        properties.put("mybatis-plus.mapper-locations", "classpath:/ai-fixture-no-xml/*.xml");
        properties.put("spring.data.redis.host", "127.0.0.1");
        properties.put("spring.data.redis.port", "16380");
        properties.put("spring.data.redis.database", "0");
        properties.put("erp.ai.enabled", "true");
        properties.put("erp.ai.api-key", "local-fixture-no-network");
        properties.put("sa-token.token-name", "satoken");
        properties.put("sa-token.is-log", "false");
        properties.put("spring.main.banner-mode", "off");
        application.setDefaultProperties(properties);
        application.run(args);
    }

    @Bean(destroyMethod = "shutdown")
    RedissonClient testRedis(@Value("${spring.data.redis.host}") String host,
                             @Value("${spring.data.redis.port}") int port,
                             @Value("${spring.data.redis.database}") int database) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectionMinimumIdleSize(1)
                .setConnectionPoolSize(8)
                .setConnectTimeout(2000)
                .setTimeout(3000)
                .setRetryAttempts(0);
        return Redisson.create(config);
    }

    @Bean
    RedisConnectionFactory testRedisConnectionFactory(RedissonClient redis) {
        return new RedissonConnectionFactory(redis);
    }

    @Bean
    TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean("deepseekFlashChatModel")
    ChatModel testPlanningModel(ObjectMapper mapper) {
        GraphUiIntegrationApplication.ScenarioModel scenario = new GraphUiIntegrationApplication.ScenarioModel(mapper);
        return prompt -> {
            String input = prompt.getContents();
            if (input.contains("触发模型异常")) {
                throw new IllegalStateException("仅用于验证失败收尾的受控模型异常");
            }
            if (input.contains("慢任务")) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("测试任务被中断", interrupted);
                }
            }
            boolean repair = input.contains("本次为输出格式修复");
            if (input.contains("触发格式耗尽") || (input.contains("触发格式修复") && !repair)) {
                return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"title\": }"))));
            }
            return scenario.call(prompt);
        };
    }

    @Bean("deepseekContextSummaryChatModel")
    ChatModel testSummaryModel() {
        return prompt -> new ChatResponse(List.of(new Generation(
                new AssistantMessage("此前已完成经营分析规划；历史原文仍可追溯。"))));
    }
}
