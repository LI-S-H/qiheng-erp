package com.qiheng.erp.ai.config;

import com.qiheng.erp.ai.config.properties.AiGraphProperties;
import com.qiheng.erp.ai.config.properties.AiMemoryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图与记忆配置的绑定契约。
 *
 * <p>重点是两处容易出错的类型：{@code summary-max-history-ratio} 是 double，
 * 配成 {@code 0.10} 不能变成 0；{@code summary-timeout-seconds} 是 long，
 * 摘要模型与压缩器共用同一个值。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
class AiGraphMemoryPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiPropertiesConfiguration.class);

    @Test
    void bindsGraphAndMemoryFields() {
        runner.withPropertyValues(
                        "erp.ai.graph.max-format-retries=3",
                        "erp.ai.graph.max-user-needs=4",
                        "erp.ai.graph.max-derived-needs=8",
                        "erp.ai.graph.max-review-rounds=5",
                        "erp.ai.memory.summary-trigger-tokens=100000",
                        "erp.ai.memory.messages-to-keep=10",
                        "erp.ai.memory.summary-max-tokens=20000",
                        "erp.ai.memory.summary-max-history-ratio=0.25",
                        "erp.ai.memory.summary-provider-max-output-tokens=30000",
                        "erp.ai.memory.summary-timeout-seconds=120")
                .run(context -> {
                    AiGraphProperties graph = context.getBean(AiGraphProperties.class);
                    assertThat(graph.maxFormatRetries()).isEqualTo(3);
                    assertThat(graph.maxUserNeeds()).isEqualTo(4);
                    assertThat(graph.maxDerivedNeeds()).isEqualTo(8);
                    assertThat(graph.maxReviewRounds()).isEqualTo(5);

                    AiMemoryProperties memory = context.getBean(AiMemoryProperties.class);
                    assertThat(memory.summaryTriggerTokens()).isEqualTo(100000);
                    assertThat(memory.messagesToKeep()).isEqualTo(10);
                    assertThat(memory.summaryMaxTokens()).isEqualTo(20000);
                    assertThat(memory.summaryMaxHistoryRatio()).isEqualTo(0.25);
                    assertThat(memory.summaryProviderMaxOutputTokens()).isEqualTo(30000);
                    assertThat(memory.summaryTimeoutSeconds()).isEqualTo(120L);
                });
    }

    @Test
    void appliesDefaultsWhenKeysMissing() {
        runner.run(context -> {
            AiGraphProperties graph = context.getBean(AiGraphProperties.class);
            assertThat(graph.maxFormatRetries()).isEqualTo(2);
            assertThat(graph.maxUserNeeds()).isEqualTo(3);
            assertThat(graph.maxDerivedNeeds()).isEqualTo(6);
            assertThat(graph.maxReviewRounds()).isEqualTo(4);

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