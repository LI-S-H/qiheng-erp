package com.qiheng.erp.ai.config;

import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 经验配置的绑定契约：默认值决定提取频率、预算和注入上限。
 *
 * @author Li
 * @since 2026-11-20
 */
class AiExperiencePropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiPropertiesConfiguration.class);

    @Test
    void bindsAllFields() {
        runner.withPropertyValues(
                        "erp.ai.experience.enabled=false",
                        "erp.ai.experience.cron=0 0 4 * * ?",
                        "erp.ai.experience.max-tasks-per-day=5",
                        "erp.ai.experience.lookback-days=3",
                        "erp.ai.experience.min-events=4",
                        "erp.ai.experience.extraction-input-max-tokens=1000",
                        "erp.ai.experience.extraction-max-output-tokens=500",
                        "erp.ai.experience.merge-input-max-tokens=40000",
                        "erp.ai.experience.merge-max-output-tokens=12000",
                        "erp.ai.experience.merge-page-size=50",
                        "erp.ai.experience.extraction-max-accepted=12",
                        "erp.ai.experience.max-experiences-per-task=2",
                        "erp.ai.experience.max-text-chars=100",
                        "erp.ai.experience.merge-max-experiences=20",
                        "erp.ai.experience.summary-max-chars=200",
                        "erp.ai.experience.search-limit=3",
                        "erp.ai.experience.cache-seconds=10",
                        "erp.ai.experience.model=pro")
                .run(context -> {
                    AiExperienceProperties properties = context.getBean(AiExperienceProperties.class);

                    assertThat(properties.enabled()).isFalse();
                    assertThat(properties.cron()).isEqualTo("0 0 4 * * ?");
                    assertThat(properties.maxTasksPerDay()).isEqualTo(5);
                    assertThat(properties.lookbackDays()).isEqualTo(3);
                    assertThat(properties.minEvents()).isEqualTo(4);
                    assertThat(properties.extractionInputMaxTokens()).isEqualTo(1000);
                    assertThat(properties.extractionMaxOutputTokens()).isEqualTo(500);
                    assertThat(properties.mergeInputMaxTokens()).isEqualTo(40000);
                    assertThat(properties.mergeMaxOutputTokens()).isEqualTo(12000);
                    assertThat(properties.mergePageSize()).isEqualTo(50);
                    assertThat(properties.extractionMaxAccepted()).isEqualTo(12);
                    assertThat(properties.maxExperiencesPerTask()).isEqualTo(2);
                    assertThat(properties.maxTextChars()).isEqualTo(100);
                    assertThat(properties.mergeMaxExperiences()).isEqualTo(20);
                    assertThat(properties.summaryMaxChars()).isEqualTo(200);
                    assertThat(properties.searchLimit()).isEqualTo(3);
                    assertThat(properties.cacheSeconds()).isEqualTo(10L);
                    assertThat(properties.model()).isEqualTo("pro");
                });
    }

    @Test
    void missingBlockStillGetsDefaults() {
        runner.run(context -> {
            AiExperienceProperties properties = context.getBean(AiExperienceProperties.class);

            assertThat(properties.enabled()).isTrue();
            assertThat(properties.maxTasksPerDay()).isEqualTo(100);
            assertThat(properties.lookbackDays()).isEqualTo(7);
            assertThat(properties.minEvents()).isEqualTo(6);
            assertThat(properties.extractionInputMaxTokens()).isEqualTo(600_000);
            assertThat(properties.extractionMaxOutputTokens()).isEqualTo(4000);
            assertThat(properties.mergeInputMaxTokens()).isEqualTo(80000);
            assertThat(properties.mergeMaxOutputTokens()).isEqualTo(35000);
            assertThat(properties.mergePageSize()).isEqualTo(100);
            assertThat(properties.extractionMaxAccepted()).isEqualTo(10);
            assertThat(properties.maxExperiencesPerTask()).isEqualTo(5);
            assertThat(properties.maxTextChars()).isEqualTo(400);
            assertThat(properties.mergeMaxExperiences()).isEqualTo(100);
            assertThat(properties.summaryMaxChars()).isEqualTo(400);
            assertThat(properties.searchLimit()).isEqualTo(10);
            assertThat(properties.cacheSeconds()).isEqualTo(60L);
            assertThat(properties.model()).isEqualTo("flash");
        });
    }
}
