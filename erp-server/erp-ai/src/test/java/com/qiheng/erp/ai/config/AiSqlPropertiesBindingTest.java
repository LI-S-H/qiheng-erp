package com.qiheng.erp.ai.config;

import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.service.data.sql.pool.ReadOnlySqlPoolSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 取数配置的绑定契约。
 *
 * <p>钉住三件事：yml 里的 kebab-case 键能绑到驼峰字段；缺键时 {@code @DefaultValue}
 * 兜得住；空口令还是空串。这三件事任一出问题，取数底座都会在启动或首次查询时炸，
 * 而且报错位置离真正的原因很远。</p>
 *
 * @author Li
 * @since 2026-10-26
 */
class AiSqlPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AiPropertiesConfiguration.class);

    @Test
    void bindsAllFields() {
        runner.withPropertyValues(
                        "erp.ai.sql.datasource.url=jdbc:mysql://localhost:13307/erp",
                        "erp.ai.sql.datasource.username=erp_ai_ro",
                        "erp.ai.sql.datasource.password=s3cret",
                        "erp.ai.sql.datasource.max-active=4",
                        // 特意用 kebab-case，验宽松绑定
                        "erp.ai.sql.datasource.max-wait-ms=5000",
                        "erp.ai.sql.session-read-only=false",
                        "erp.ai.sql.page-size=50",
                        "erp.ai.sql.max-total-rows=1000",
                        "erp.ai.sql.max-pages=3",
                        "erp.ai.sql.query-timeout-seconds=7")
                .run(context -> {
                    AiSqlProperties properties = context.getBean(AiSqlProperties.class);

                    assertThat(properties.datasource().url())
                            .isEqualTo("jdbc:mysql://localhost:13307/erp");
                    assertThat(properties.datasource().username()).isEqualTo("erp_ai_ro");
                    assertThat(properties.datasource().password()).isEqualTo("s3cret");
                    assertThat(properties.datasource().maxActive()).isEqualTo(4);
                    assertThat(properties.datasource().maxWaitMs()).isEqualTo(5000L);
                    assertThat(properties.sessionReadOnly()).isFalse();
                    assertThat(properties.pageSize()).isEqualTo(50);
                    assertThat(properties.maxTotalRows()).isEqualTo(1000);
                    assertThat(properties.maxPages()).isEqualTo(3);
                    assertThat(properties.queryTimeoutSeconds()).isEqualTo(7);

                    ReadOnlySqlPoolSettings settings = properties.toSettings();
                    assertThat(settings.sessionReadOnly()).isFalse();
                    assertThat(settings.maxActive()).isEqualTo(4);
                });
    }

    @Test
    void appliesDefaultsWhenKeysMissing() {
        runner.withPropertyValues("erp.ai.sql.datasource.url=jdbc:mysql://localhost:13307/erp")
                .run(context -> {
                    AiSqlProperties properties = context.getBean(AiSqlProperties.class);

                    assertThat(properties.datasource().driverClassName())
                            .isEqualTo("com.mysql.cj.jdbc.Driver");
                    assertThat(properties.datasource().poolName()).isEqualTo("erp-ai-readonly");
                    assertThat(properties.datasource().validationSql()).isEqualTo("SELECT 1");
                    assertThat(properties.datasource().initialSize()).isEqualTo(1);
                    assertThat(properties.datasource().minIdle()).isEqualTo(1);
                    assertThat(properties.datasource().maxActive()).isEqualTo(2);
                    assertThat(properties.datasource().slowSqlMillis()).isEqualTo(1000L);
                    assertThat(properties.sessionReadOnly()).isTrue();
                    assertThat(properties.pageSize()).isEqualTo(200);
                    assertThat(properties.maxTotalRows()).isEqualTo(5000);
                    assertThat(properties.maxPages()).isEqualTo(10);
                    assertThat(properties.queryTimeoutSeconds()).isEqualTo(10);
                });
    }

    @Test
    void blankPasswordStaysEmptyString() {
        // application-ai.yml 里写的是 ${ERP_AI_SQL_PASSWORD:}，绑定层会给出 null；
        // 原来 @Value 拿到的是空串，这里必须保持空串
        runner.withPropertyValues(
                        "erp.ai.sql.datasource.url=jdbc:mysql://localhost:13307/erp",
                        "erp.ai.sql.datasource.password=")
                .run(context -> assertThat(context.getBean(AiSqlProperties.class)
                        .toSettings().password()).isEmpty());
    }

    @Test
    void missingDatasourceFailsWithClearMessage() {
        runner.run(context -> assertThatThrownBy(
                        () -> context.getBean(AiSqlProperties.class).toSettings())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("erp.ai.sql.datasource"));
    }
}