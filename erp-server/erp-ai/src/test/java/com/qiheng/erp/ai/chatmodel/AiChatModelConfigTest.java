package com.qiheng.erp.ai.chatmodel;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.qiheng.erp.ai.agent.AiAgentConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class AiChatModelConfigTest {
    @TempDir
    Path configurationDirectory;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DeepSeekProChatModelConfig.class, DeepSeekFlashChatModelConfig.class, AiAgentConfig.class);

    @Test
    void disabledAiStartsWithoutKeyOrModel() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ChatModel.class).doesNotHaveBean(ReactAgent.class);
        });
    }

    @Test
    void enabledWithoutKeyStillStartsWithoutModel() {
        runner.withPropertyValues("erp.ai.enabled=true", "erp.ai.api-key=").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(ChatModel.class).doesNotHaveBean(ReactAgent.class);
        });
    }

    @Test
    void validConfigurationCreatesTwoModelsAndOneAgentWithoutRemoteCall() {
        runner.withPropertyValues("erp.ai.enabled=true", "erp.ai.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ReactAgent.class);
                    assertThat(context.getBeansOfType(ChatModel.class)).hasSize(2);
                    assertThat(context.getBean("deepseekProChatModel"))
                            .isNotSameAs(context.getBean("deepseekFlashChatModel"));
                    assertThat(context.getBean("deepseekProChatModel", ChatModel.class).getDefaultOptions().getModel())
                            .isEqualTo("deepseek-v4-pro");
                    assertThat(context.getBean("deepseekFlashChatModel", ChatModel.class).getDefaultOptions().getModel())
                            .isEqualTo("deepseek-flash");
                    assertThat(context).hasBean("orchestratorAgent");
                });
    }

    @Test
    void applicationConfigurationLoadsWithOptionalLocalKeyFile() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=classpath:application-ai.yml",
                        "erp.ai.enabled=true", "erp.ai.api-key=test-key")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ReactAgent.class);
                    assertThat(context.getBeansOfType(ChatModel.class)).hasSize(2);
                    assertThat(context.getEnvironment().getProperty("erp.ai.base-url"))
                            .isEqualTo("https://api.deepseek.com");
                });
    }

    @Test
    void localKeyFileEnablesBothModelsWithoutIdeEnvironmentVariables() throws Exception {
        Path local = configurationDirectory.resolve(".env.ai.yaml");
        Files.writeString(local, "erp:\n  ai:\n    enabled: true\n    api-key: test-key\n");
        String defaults;
        try (var stream = getClass().getResourceAsStream("/application-ai.yml")) {
            defaults = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        Path application = configurationDirectory.resolve("application-ai.yml");
        Files.writeString(application, defaults.replace("optional:file:./.env.ai.yaml",
                "optional:" + local.toUri()));
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=" + application.toUri())
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ReactAgent.class);
                    assertThat(context.getBeansOfType(ChatModel.class)).hasSize(2);
                    assertThat(context.getEnvironment().getProperty("erp.ai.api-key")).isEqualTo("test-key");
                });
    }

}
