package com.qiheng.erp.ai.config;

import com.qiheng.erp.ai.config.properties.AiMemoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 保留的摘要记忆配置绑定契约。
 *
 * <p>重点是两处容易出错的类型：{@code summary-max-history-ratio} 是 double，
 * 配成 {@code 0.10} 不能变成 0；{@code summary-timeout-seconds} 是 long，
 * 摘要模型与压缩器共用同一个值。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
class AiMemoryPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiPropertiesConfiguration.class);

    /** 自定义摘要预算仍按原有数值类型绑定。 */
    @Test
    void bindsMemoryFields() {
        runner.withPropertyValues(
                        "erp.ai.memory.summary-trigger-tokens=100000",
                        "erp.ai.memory.messages-to-keep=10",
                        "erp.ai.memory.summary-max-tokens=20000",
                        "erp.ai.memory.summary-max-history-ratio=0.25",
                        "erp.ai.memory.summary-provider-max-output-tokens=30000",
                        "erp.ai.memory.summary-timeout-seconds=120")
                .run(context -> {
                    AiMemoryProperties memory = context.getBean(AiMemoryProperties.class);
                    assertThat(memory.summaryTriggerTokens()).isEqualTo(100000);
                    assertThat(memory.messagesToKeep()).isEqualTo(10);
                    assertThat(memory.summaryMaxTokens()).isEqualTo(20000);
                    assertThat(memory.summaryMaxHistoryRatio()).isEqualTo(0.25);
                    assertThat(memory.summaryProviderMaxOutputTokens()).isEqualTo(30000);
                    assertThat(memory.summaryTimeoutSeconds()).isEqualTo(120L);
                });
    }

    /** 不配置摘要参数时保留原有默认预算。 */
    @Test
    void appliesDefaultsWhenKeysMissing() {
        runner.run(context -> {
            AiMemoryProperties memory = context.getBean(AiMemoryProperties.class);
            assertThat(memory.summaryTriggerTokens()).isEqualTo(200000);
            assertThat(memory.messagesToKeep()).isEqualTo(20);
            assertThat(memory.summaryMaxTokens()).isEqualTo(50000);
            assertThat(memory.summaryMaxHistoryRatio()).isEqualTo(0.10);
            assertThat(memory.summaryProviderMaxOutputTokens()).isEqualTo(50000);
            assertThat(memory.summaryTimeoutSeconds()).isEqualTo(300L);
        });
    }
}
