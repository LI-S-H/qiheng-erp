package com.qiheng.erp.ai.config;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.dto.GraphStartRequest;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.memory.node.NodeMemoryStore;
import com.qiheng.erp.ai.service.GraphService;
import com.qiheng.erp.ai.service.impl.GraphServiceImpl;
import com.qiheng.erp.ai.service.progress.RunProgressPublisher;
import com.qiheng.erp.ai.graph.support.ConversationExecutionLock;
import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.graph.support.ConversationThreads;
import com.qiheng.erp.ai.graph.support.NodeMemoryRecorder;
import com.qiheng.erp.ai.graph.runtime.RunCoordinator;
import com.qiheng.erp.ai.graph.runtime.RunFailureHandler;
import com.qiheng.erp.ai.graph.runtime.RunResumePreparer;
import com.qiheng.erp.ai.graph.runtime.RunStartPreparer;
import com.qiheng.erp.ai.graph.runtime.RunStateResolver;
import com.qiheng.erp.ai.service.VisibleMessageCodec;
import com.qiheng.erp.ai.service.VisibleResultFactory;
import org.springframework.transaction.support.TransactionTemplate;
import com.qiheng.erp.common.exception.ServiceUnavailableBizException;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class GraphDisabledConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class, ObjectMapper::new)
            // 落库与进度依赖只在真正执行图时才会用到，这里只需要让上下文能装配起来
            .withBean(NodeMemoryStore.class, () -> mock(NodeMemoryStore.class))
            .withBean(AiMessageMapper.class, () -> mock(AiMessageMapper.class))
            .withBean(AiConversationMapper.class, () -> mock(AiConversationMapper.class))
            .withBean(RunProgressPublisher.class, () -> mock(RunProgressPublisher.class))
            .withBean(ConversationExecutionLock.class, () -> mock(ConversationExecutionLock.class))
            .withBean(TransactionTemplate.class, () -> mock(TransactionTemplate.class))
            .withBean(VisibleMessageCodec.class, () -> new VisibleMessageCodec(new ObjectMapper()))
            .withBean(ConversationThreads.class, ConversationThreads::new)
            .withBean(VisibleResultFactory.class, VisibleResultFactory::new)
            .withBean(ConversationMessageStore.class, () -> mock(ConversationMessageStore.class))
            .withBean(NodeMemoryRecorder.class, () -> mock(NodeMemoryRecorder.class))
            .withUserConfiguration(
                    DeepSeekChatModelConfig.class,
                    RedisSaverConfiguration.class,
                    GraphConfiguration.class,
                    GraphServiceImpl.class,
                    RunCoordinator.class,
                    RunStateResolver.class,
                    RunStartPreparer.class,
                    RunResumePreparer.class,
                    RunFailureHandler.class);

    @ParameterizedTest
    @CsvSource({
            "false, disabled-test-value",
            "true, sk-placeholder",
            "true, ''"
    })
    void disabledOrMissingKeyDoesNotRequireRedisOrModel(String enabled, String apiKey) {
        runner.withPropertyValues("erp.ai.enabled=" + enabled, "erp.ai.api-key=" + apiKey)
                .run(context -> {
                    assertThat(context).hasNotFailed()
                            .hasSingleBean(GraphService.class)
                            .doesNotHaveBean(ChatModel.class)
                            .doesNotHaveBean(RedisSaver.class)
                            .doesNotHaveBean(CompiledGraph.class);

                    LoginUser user = new LoginUser();
                    user.setUserId(101L);
                    try (MockedStatic<UserContext> users = mockStatic(UserContext.class)) {
                        users.when(UserContext::requireCurrentUser).thenReturn(user);
                        assertThatThrownBy(() -> context.getBean(GraphService.class)
                                .stream(new GraphStartRequest(1L, "查询销量")))
                                .isInstanceOf(ServiceUnavailableBizException.class);
                    }
                });
    }
}
