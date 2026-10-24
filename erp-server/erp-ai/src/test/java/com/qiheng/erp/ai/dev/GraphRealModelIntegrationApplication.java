package com.qiheng.erp.ai.dev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.GraphConfiguration;
import com.qiheng.erp.ai.config.RedisSaverConfiguration;
import com.qiheng.erp.ai.controller.AiAssistantController;
import com.qiheng.erp.ai.controller.GraphController;
import com.qiheng.erp.ai.graph.runtime.RunCoordinator;
import com.qiheng.erp.ai.graph.runtime.RunFailureHandler;
import com.qiheng.erp.ai.graph.runtime.RunResumePreparer;
import com.qiheng.erp.ai.graph.runtime.RunStartPreparer;
import com.qiheng.erp.ai.graph.runtime.RunStateResolver;
import com.qiheng.erp.ai.graph.support.ConversationExecutionLock;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.memory.node.NodeMemoryStore;
import com.qiheng.erp.ai.service.VisibleMessageCodec;
import com.qiheng.erp.ai.service.VisibleResultFactory;
import com.qiheng.erp.ai.service.impl.AiAssistantServiceImpl;
import com.qiheng.erp.ai.service.impl.GraphServiceImpl;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import com.qiheng.erp.common.config.JacksonConfig;
import com.qiheng.erp.common.config.MybatisPlusConfig;
import com.qiheng.erp.security.config.SaTokenConfig;
import com.qiheng.erp.security.interceptor.StpInterfaceImpl;
import com.qiheng.erp.security.service.impl.AuthServiceImpl;
import com.qiheng.erp.security.service.impl.LoginUserServiceImpl;
import org.mybatis.spring.annotation.MapperScan;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.spring.data.connection.RedissonConnectionFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 真实模型联调应用：真库、真 Redis、真模型、真鉴权、真 HTTP/SSE。
 *
 * <p>与 {@link GraphUiDatabaseIntegrationApplication} 的差别只有两处：
 * 用的模型是真的（从 {@code erp.ai.api-key} 读），连的是主开发库（不是隔离测试库）。
 * 其余全部是生产实现：图拓扑、节点记忆、官方检查点、Mapper、事务、鉴权链路。</p>
 *
 * <p>启动：</p>
 * <pre>
 * cd erp-server
 * mvn -pl erp-ai -am install -DskipTests
 * mvn -pl erp-admin -o package -DskipTests
 * java -cp erp-ai/target/test-classes:&lt;deps&gt; com.qiheng.erp.ai.dev.GraphRealModelIntegrationApplication \
 *      -Dgraph.real.api-key=xxx
 * </pre>
 *
 * <p>本类只在 test classpath，不进生产包。</p>
 *
 * @author Li
 * @since 2026-10-22
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "org.redisson.spring.starter.RedissonAutoConfigurationV2"
})
@MapperScan({"com.qiheng.erp.ai.mapper", "com.qiheng.erp.system.mapper"})
@Import({GraphConfiguration.class, RedisSaverConfiguration.class,
        GraphController.class, AiAssistantController.class,
        GraphServiceImpl.class, AiAssistantServiceImpl.class,
        ConversationExecutionLock.class, VisibleMessageCodec.class, VisibleResultFactory.class,
        ConversationMessageStore.class, NodeMemoryRecorder.class, ConversationThreads.class,
        RunStartPreparer.class, RunResumePreparer.class, RunFailureHandler.class,
        RunCoordinator.class, RunStateResolver.class,
        RunProgressPublisher.class, NodeMemoryStore.class,
        SaTokenConfig.class, StpInterfaceImpl.class,
        AuthServiceImpl.class, LoginUserServiceImpl.class,
        com.qiheng.erp.common.util.PasswordUtil.class,
        JacksonConfig.class, MybatisPlusConfig.class,
        // 受控数据底座：授权 Schema 与业务口径都是 @Component，但本应用只扫 dev 包，需显式引入
        com.qiheng.erp.ai.service.data.schema.AuthorizedSchemaProvider.class,
        com.qiheng.erp.ai.service.data.schema.SchemaRenderer.class,
        com.qiheng.erp.ai.service.data.schema.SchemaCache.class,
        com.qiheng.erp.ai.service.knowledge.AuthorizedKnowledgeProvider.class,
        GraphRealModelIntegrationApplication.TestEndpoints.class,
        GraphUiIntegrationApplication.TestExceptionAdvice.class})
public class GraphRealModelIntegrationApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(GraphRealModelIntegrationApplication.class);
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.application.name", "graph-real-model-integration");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", "18085");
        // 主开发库：元数据登记表与业务种子都在这里，AI 链路才能看到真实表结构与真实数据
        properties.put("spring.datasource.url",
                "jdbc:mysql://127.0.0.1:13307/erp?useUnicode=true&characterEncoding=UTF-8"
                        + "&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false");
        properties.put("spring.datasource.username", "root");
        properties.put("spring.datasource.password", "123456");
        properties.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
        properties.put("mybatis-plus.mapper-locations", "classpath*:/mapper/*.xml");
        properties.put("spring.data.redis.host", "127.0.0.1");
        properties.put("spring.data.redis.port", "6379");
        properties.put("spring.data.redis.database", "0");
        // AI 打开：真实模型需要 api-key，同时它决定图是否被装配
        properties.put("erp.ai.enabled", "true");
        properties.put("erp.ai.api-key", System.getProperty("graph.real.api-key", ""));
        properties.put("erp.ai.base-url", System.getProperty("graph.real.base-url", "https://api.deepseek.com"));
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

    /** 规划用真实模型：与生产同型号同温度（Flash）。 */
    @Bean("deepseekFlashChatModel")
    ChatModel realPlanningModel(@Value("${erp.ai.api-key}") String apiKey,
                                @Value("${erp.ai.base-url}") String baseUrl) {
        return realModel(apiKey, baseUrl, "deepseek-flash");
    }

    /** 摘要用真实模型。 */
    @Bean("deepseekContextSummaryChatModel")
    ChatModel realSummaryModel(@Value("${erp.ai.api-key}") String apiKey,
                               @Value("${erp.ai.base-url}") String baseUrl) {
        return realModel(apiKey, baseUrl, "deepseek-flash");
    }

    private ChatModel realModel(String apiKey, String baseUrl, String modelName) {
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder().baseUrl(baseUrl).apiKey(apiKey).build())
                .defaultOptions(OpenAiChatOptions.builder().model(modelName).temperature(0.2).build())
                .build();
    }

    /**
     * 联调端点：登录与身份回显，业务接口全部走生产实现。
     *
     * <p>登录走真实的 {@link com.qiheng.erp.security.service.AuthService}，
     * 权限码从库里按角色读出来写进 Session——AI 侧读的就是同一份 Session 快照，
     * 所以联调看到的可见范围与真实登录完全一致。</p>
     */
    @org.springframework.web.bind.annotation.RestController
    static class TestEndpoints {

        private final com.qiheng.erp.security.service.AuthService authService;

        TestEndpoints(com.qiheng.erp.security.service.AuthService authService) {
            this.authService = authService;
        }

        @org.springframework.web.bind.annotation.PostMapping("/auth/login")
        com.qiheng.erp.common.result.Result<com.qiheng.erp.security.domain.dto.LoginResponse> login(
                @org.springframework.web.bind.annotation.RequestBody
                com.qiheng.erp.security.domain.dto.LoginRequest request) {
            return com.qiheng.erp.common.result.Result.ok(authService.login(request));
        }

        @org.springframework.web.bind.annotation.GetMapping("/auth/me")
        com.qiheng.erp.common.result.Result<com.qiheng.erp.security.domain.dto.LoginUser> currentUser() {
            return com.qiheng.erp.common.result.Result.ok(
                    com.qiheng.erp.security.context.UserContext.requireCurrentUser());
        }
    }
}
