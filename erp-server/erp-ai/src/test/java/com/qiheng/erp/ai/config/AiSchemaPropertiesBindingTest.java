package com.qiheng.erp.ai.config;

import com.qiheng.erp.ai.config.properties.AiSchemaProperties;
import com.qiheng.erp.ai.sql.schema.SchemaCache;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema 缓存配置的绑定与装配契约。
 *
 * @author Li
 * @since 2026-10-26
 */
class AiSchemaPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiPropertiesConfiguration.class, AiSchemaConfiguration.class);

    @Test
    void bindsTtlAndCreatesCache() {
        runner.withPropertyValues("erp.ai.schema.cache-ttl-seconds=60")
                .run(context -> {
                    assertThat(context.getBean(AiSchemaProperties.class).cacheTtlSeconds())
                            .isEqualTo(60L);
                    assertThat(context.getBean(SchemaCache.class)).isNotNull();
                });
    }

    @Test
    void appliesDefaultTtl() {
        runner.run(context -> assertThat(context.getBean(AiSchemaProperties.class)
                .cacheTtlSeconds()).isEqualTo(300L));
    }
}