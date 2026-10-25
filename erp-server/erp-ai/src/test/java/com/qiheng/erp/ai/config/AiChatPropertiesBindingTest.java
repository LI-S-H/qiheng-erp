package com.qiheng.erp.ai.config;

import com.qiheng.erp.ai.config.properties.AiChatProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型配置的绑定契约。
 *
 * <p>重点钉住两处：{@code erp.ai.model} 整块缺失时仍有默认值（原 {@code @Value}
 * 的每个键都带默认值，不能因为换绑定方式变成 NPE）；只配了部分字段时，
 * 其余字段仍然走 {@code @DefaultValue}。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
class AiChatPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiPropertiesConfiguration.class);

    @Test
    void bindsAllFields() {
        runner.withPropertyValues(
                        "erp.ai.api-key=sk-test",
                        "erp.ai.base-url=https://gateway.example.com",
                        "erp.ai.temperature=0.7",
                        "erp.ai.model.pro=pro-x",
                        "erp.ai.model.flash=flash-y",
                        "erp.ai.model.enable-thinking=false",
                        "erp.ai.model.read-timeout-seconds=120",
                        "erp.ai.model.flash-read-timeout-seconds=30")
                .run(context -> {
                    AiChatProperties chat = context.getBean(AiChatProperties.class);

                    assertThat(chat.apiKey()).isEqualTo("sk-test");
                    assertThat(chat.baseUrl()).isEqualTo("https://gateway.example.com");
                    assertThat(chat.temperature()).isEqualTo(0.7);
                    assertThat(chat.model().pro()).isEqualTo("pro-x");
                    assertThat(chat.model().flash()).isEqualTo("flash-y");
                    assertThat(chat.model().enableThinking()).isEqualTo("false");
                    assertThat(chat.model().readTimeoutSeconds()).isEqualTo(120L);
                    assertThat(chat.model().flashReadTimeoutSeconds()).isEqualTo(30L);
                });
    }

    @Test
    void missingModelBlockStillGetsDefaults() {
        // 原 @Value 每个键都有默认值，整块缺失也能启动；绑定层必须保持这个行为
        runner.withPropertyValues("erp.ai.api-key=sk-test")
                .run(context -> {
                    AiChatProperties chat = context.getBean(AiChatProperties.class);

                    assertThat(chat.baseUrl()).isEqualTo("https://api.deepseek.com");
                    assertThat(chat.temperature()).isEqualTo(0.2);
                    assertThat(chat.model().pro()).isEqualTo("deepseek-v4-pro");
                    assertThat(chat.model().flash()).isEqualTo("deepseek-flash");
                    assertThat(chat.model().enableThinking()).isNull();
                    assertThat(chat.model().readTimeoutSeconds()).isEqualTo(300L);
                    assertThat(chat.model().flashReadTimeoutSeconds()).isEqualTo(60L);
                });
    }

    @Test
    void partialModelBlockFillsRemainingDefaults() {
        runner.withPropertyValues(
                        "erp.ai.api-key=sk-test",
                        "erp.ai.model.pro=only-pro")
                .run(context -> {
                    AiChatProperties chat = context.getBean(AiChatProperties.class);

                    assertThat(chat.model().pro()).isEqualTo("only-pro");
                    assertThat(chat.model().flash()).isEqualTo("deepseek-flash");
                    assertThat(chat.model().readTimeoutSeconds()).isEqualTo(300L);
                });
    }

    @Test
    void missingApiKeyIsNullNotFailure() {
        // 缺密钥时属性 Bean 照样绑定成功；装配模型由 AiModelEnabledCondition 负责挡，
        // 这里只钉住"绑定本身不报错"，避免有人误加 @NotBlank 把 AI 关闭的环境弄挂
        runner.run(context -> assertThat(context.getBean(AiChatProperties.class).apiKey()).isNull());
    }
}