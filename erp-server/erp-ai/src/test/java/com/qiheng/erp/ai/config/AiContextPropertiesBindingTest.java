package com.qiheng.erp.ai.config;

import com.qiheng.erp.ai.config.properties.AiContextProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 上下文压缩配置的绑定契约。
 *
 * <p>压缩阈值决定什么时候替换历史，默认值必须与百万窗口的假设一致；
 * 只覆盖部分字段时，其余字段仍走 {@code @DefaultValue}。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
class AiContextPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiPropertiesConfiguration.class);

    @Test
    void bindsAllFields() {
        runner.withPropertyValues(
                        "erp.ai.context.enabled=false",
                        "erp.ai.context.max-tokens-before-summary=1000",
                        "erp.ai.context.keep-tokens=200",
                        "erp.ai.context.min-keep-messages=4",
                        "erp.ai.context.keep-first-user-message=true",
                        "erp.ai.context.summary-model=pro",
                        "erp.ai.context.summary-timeout-seconds=60",
                        "erp.ai.context.summary-max-output-tokens=4000",
                        "erp.ai.context.summary-input-max-tokens=5000",
                        "erp.ai.context.source-tool-clip-chars=100",
                        "erp.ai.context.source-message-clip-chars=200")
                .run(context -> {
                    AiContextProperties properties = context.getBean(AiContextProperties.class);

                    assertThat(properties.enabled()).isFalse();
                    assertThat(properties.maxTokensBeforeSummary()).isEqualTo(1000);
                    assertThat(properties.keepTokens()).isEqualTo(200);
                    assertThat(properties.minKeepMessages()).isEqualTo(4);
                    assertThat(properties.keepFirstUserMessage()).isTrue();
                    assertThat(properties.summaryModel()).isEqualTo("pro");
                    assertThat(properties.summaryTimeoutSeconds()).isEqualTo(60L);
                    assertThat(properties.summaryMaxOutputTokens()).isEqualTo(4000);
                    assertThat(properties.summaryInputMaxTokens()).isEqualTo(5000);
                    assertThat(properties.sourceToolClipChars()).isEqualTo(100);
                    assertThat(properties.sourceMessageClipChars()).isEqualTo(200);
                });
    }

    @Test
    void missingBlockStillGetsDefaults() {
        runner.run(context -> {
            AiContextProperties properties = context.getBean(AiContextProperties.class);

            assertThat(properties.enabled()).isTrue();
            assertThat(properties.maxTokensBeforeSummary()).isEqualTo(650_000);
            assertThat(properties.keepTokens()).isEqualTo(60_000);
            assertThat(properties.minKeepMessages()).isEqualTo(8);
            assertThat(properties.keepFirstUserMessage()).isFalse();
            assertThat(properties.summaryModel()).isEqualTo("flash");
            assertThat(properties.summaryTimeoutSeconds()).isEqualTo(180L);
            assertThat(properties.summaryMaxOutputTokens()).isEqualTo(8000);
            assertThat(properties.summaryInputMaxTokens()).isEqualTo(800_000);
            assertThat(properties.sourceToolClipChars()).isEqualTo(1000);
            assertThat(properties.sourceMessageClipChars()).isEqualTo(2000);
        });
    }
}