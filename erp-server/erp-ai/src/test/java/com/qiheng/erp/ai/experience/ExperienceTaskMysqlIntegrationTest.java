package com.qiheng.erp.ai.experience;

import com.qiheng.erp.ai.mapper.AiExperienceTaskMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.assertThat;

/** 在独立测试库中验证经验任务的真实 MyBatis 映射和状态变更。 */
@EnabledIfEnvironmentVariable(named = "ERP_EXPERIENCE_IT_URL", matches = ".+")
class ExperienceTaskMysqlIntegrationTest {

    @Test
    void pendingTasksAndAttemptsSurviveDatabaseRoundTrips() throws Exception {
        var dataSource = new DriverManagerDataSource(System.getenv("ERP_EXPERIENCE_IT_URL"),
                System.getenv("ERP_EXPERIENCE_IT_USER"), System.getenv("ERP_EXPERIENCE_IT_PASSWORD"));
        var factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        SqlSessionFactory factory = factoryBean.getObject();
        factory.getConfiguration().addMapper(AiExperienceTaskMapper.class);
        try (var session = factory.openSession(true)) {
            var mapper = session.getMapper(AiExperienceTaskMapper.class);
            assertThat(mapper.register(1, 1, "MAIN", "run-it")).isEqualTo(1);
            assertThat(mapper.register(1, 1, "MAIN", "run-it")).isZero();
            assertThat(mapper.pending()).singleElement().satisfies(task -> {
                assertThat(task.taskId()).isEqualTo("run-it");
                assertThat(task.attemptCount()).isZero();
            });
            assertThat(mapper.claim(1, 1, "MAIN", "run-it")).isEqualTo(1);
            assertThat(mapper.finish(1, 1, "MAIN", "run-it", "PENDING", 1)).isEqualTo(1);
            assertThat(mapper.claim(1, 1, "MAIN", "run-it")).isEqualTo(1);
            assertThat(mapper.release(1, 1, "MAIN", "run-it")).isEqualTo(1);
            assertThat(mapper.pending()).singleElement()
                    .satisfies(task -> assertThat(task.attemptCount()).isEqualTo(1));
        }
    }
}
