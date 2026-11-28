package com.qiheng.erp.ai.experience;

import com.qiheng.erp.ai.domain.entity.AiExperience;
import com.qiheng.erp.ai.mapper.AiExperienceMapper;
import com.qiheng.erp.ai.mapper.AiExperienceTaskMapper;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 校验动态 SQL 能被 MyBatis 解析且物理删除限定本次编号。 */
class ExperienceMapperSqlTest {
    @Test
    void pagingAndBatchSqlAreBoundWithScopedParameters() {
        var configuration = new Configuration();
        configuration.addMapper(AiExperienceMapper.class);
        String prefix = AiExperienceMapper.class.getName() + ".";
        String page = configuration.getMappedStatement(prefix + "selectPublishedPage")
                .getBoundSql(Map.of("agentType", "MAIN", "afterId", 9L, "limit", 100)).getSql();
        String delete = configuration.getMappedStatement(prefix + "deletePublishedByIds")
                .getBoundSql(Map.of("agentType", "MAIN", "ids", List.of(10L, 11L))).getSql();
        var item = new AiExperience().setId(10L).setAgentType("MAIN").setExperienceText("经验")
                .setStatus("PUBLISHED").setDeleted(0);
        String insert = configuration.getMappedStatement(prefix + "insertPublishedBatch")
                .getBoundSql(Map.of("items", List.of(item))).getSql();

        assertThat(page).contains("id > ?", "status = 'PUBLISHED'", "deleted = 0");
        assertThat(delete).contains("DELETE FROM ai_agent_experience", "agent_type = ?", "id IN");
        assertThat(insert).contains("INSERT INTO ai_agent_experience", "VALUES");
    }

    @Test
    void pendingTasksReadPersistedRetryCount() {
        var configuration = new Configuration();
        configuration.addMapper(AiExperienceTaskMapper.class);
        String sql = configuration.getMappedStatement(AiExperienceTaskMapper.class.getName() + ".pending")
                .getBoundSql(null).getSql();
        assertThat(sql).contains("attempt_count", "status = 'PENDING'");
    }
}
