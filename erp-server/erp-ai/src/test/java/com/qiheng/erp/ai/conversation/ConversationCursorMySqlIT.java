package com.qiheng.erp.ai.conversation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 MySQL 验证同一时间的游标边界及用户隔离。 */
@EnabledIfEnvironmentVariable(named = "MESSAGE_STORE_IT_URL", matches = ".+")
class ConversationCursorMySqlIT {
    @Test
    void cursorKeepsEqualTimestampsAndIsolatesUsers() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("MESSAGE_STORE_IT_URL"),
                System.getenv("MESSAGE_STORE_IT_USER"), System.getenv("MESSAGE_STORE_IT_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(AiConversationMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(config);
        var mapper = new SqlSessionTemplate(factory.getObject()).getMapper(AiConversationMapper.class);
        long base = Math.abs(UUID.randomUUID().getLeastSignificantBits() / 1000) * 1000;
        long user = base + 900;
        LocalDateTime timestamp = LocalDateTime.of(2026, 1, 1, 12, 0);
        try {
            for (int i = 1; i <= 52; i++) {
                jdbc.update("INSERT INTO ai_conversation (id,user_id,title,description,create_time,update_time) VALUES (?,?,?,?,?,?)",
                        base + i, user, "分页测试", "隔离测试", timestamp, timestamp);
            }
            jdbc.update("INSERT INTO ai_conversation (id,user_id,title,description,create_time,update_time) VALUES (?,?,?,?,?,?)",
                    base + 53, user + 1, "其他用户", "隔离测试", timestamp, timestamp);
            var first = mapper.selectPage(user, null, null);
            assertThat(first).hasSize(51);
            var second = mapper.selectPage(user, first.get(49).getLastMessageAt(), first.get(49).getId());
            assertThat(second).extracting(c -> c.getId()).containsExactly(base + 2, base + 1);
            assertThat(first.subList(0, 50)).noneMatch(c -> c.getId() == base + 53);
            // 旧会话可能已有过期活动时间，应使用更晚的历史消息时间。
            jdbc.update("UPDATE ai_conversation SET last_message_at=? WHERE id=?", timestamp, base + 1);
            jdbc.update("INSERT INTO ai_message (id,conversation_id,user_id,request_id,seq,message_key,visible,role,kind,content,create_time) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    base + 500, base + 1, user, "cursor-test", 1, "cursor-test", true, "user", "USER_INPUT", "\"测试\"", timestamp.plusMinutes(1));
            var refreshed = mapper.selectPage(user, null, null);
            assertThat(refreshed.getFirst().getId()).isEqualTo(base + 1);
            assertThat(refreshed.getFirst().getLastMessageAt()).isEqualTo(timestamp.plusMinutes(1));
        } finally {
            jdbc.update("DELETE FROM ai_message WHERE conversation_id BETWEEN ? AND ?", base + 1, base + 53);
            jdbc.update("DELETE FROM ai_conversation WHERE id BETWEEN ? AND ?", base + 1, base + 53);
        }
    }
}
