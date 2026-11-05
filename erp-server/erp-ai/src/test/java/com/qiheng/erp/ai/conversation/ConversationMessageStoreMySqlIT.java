package com.qiheng.erp.ai.conversation;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 隔离会话内验证真实 MySQL 的并发序号及消息幂等。 */
@EnabledIfEnvironmentVariable(named = "MESSAGE_STORE_IT_URL", matches = ".+")
class ConversationMessageStoreMySqlIT {
    @Test
    void allocatesUniqueSequencesAcrossConcurrentTransactions() throws Exception {
        var source = new DriverManagerDataSource(System.getenv("MESSAGE_STORE_IT_URL"),
                System.getenv("MESSAGE_STORE_IT_USER"), System.getenv("MESSAGE_STORE_IT_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AiMessageMapper.class);
        var bean = new MybatisSqlSessionFactoryBean();
        bean.setDataSource(source);
        bean.setConfiguration(configuration);
        var mapper = new SqlSessionTemplate(bean.getObject()).getMapper(AiMessageMapper.class);
        var store = new ConversationMessageStore(mapper);
        var transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        long conversation = Math.abs(UUID.randomUUID().getLeastSignificantBits() / 2);
        long user = 9081726354L;
        jdbc.update("INSERT INTO ai_conversation (id,user_id,title,description) VALUES (?,?,?,?)",
                conversation, user, "新的经营会话", "隔离消息测试");
        try {
            var first = message(conversation, user, "old");
            transactions.execute(status -> store.append(first));
            store.nameFirstInput(user, conversation, "分析当前库存情况");
            assertThat(jdbc.queryForObject("SELECT title FROM ai_conversation WHERE id=?", String.class, conversation))
                    .isEqualTo("分析当前库存情况");
            jdbc.update("UPDATE ai_conversation SET title=? WHERE id=?", "手动标题", conversation);
            store.nameFirstInput(user, conversation, "不能覆盖的输入");
            assertThat(jdbc.queryForObject("SELECT title FROM ai_conversation WHERE id=?", String.class, conversation))
                    .isEqualTo("手动标题");
            jdbc.update("UPDATE ai_message SET deleted=1 WHERE conversation_id=? AND message_key=?", conversation, "old");

            var ready = new CountDownLatch(8);
            var start = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(8);
            try {
                var futures = new ArrayList<java.util.concurrent.Future<AiMessage>>();
                for (int i = 0; i < 8; i++) {
                    String key = i < 2 ? "same-key" : "message-" + i;
                    futures.add(pool.submit(() -> {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("并发测试启动超时");
                        return transactions.execute(status -> store.append(message(conversation, user, key)));
                    }));
                }
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                for (var future : futures) assertThat(future.get(20, TimeUnit.SECONDS)).isNotNull();
            } finally {
                pool.shutdownNow();
            }
            var sequences = jdbc.queryForList("SELECT seq FROM ai_message WHERE conversation_id=? ORDER BY seq", Long.class, conversation);
            assertThat(sequences).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_message WHERE conversation_id=? AND message_key=?", Long.class,
                    conversation, "same-key")).isEqualTo(1);
        } finally {
            // 只删除本测试创建的隔离会话及其消息。
            jdbc.update("DELETE FROM ai_message WHERE conversation_id=? AND user_id=?", conversation, user);
            jdbc.update("DELETE FROM ai_conversation WHERE id=? AND user_id=?", conversation, user);
        }
    }

    private AiMessage message(long conversation, long user, String key) {
        return new AiMessage().setConversationId(conversation).setUserId(user).setRequestId("test-run")
                .setMessageKey(key).setRole("user").setKind("USER_INPUT").setVisible(true)
                .setContent("{\"type\":\"TEXT\",\"data\":{\"text\":\"测试\"}}");
    }
}
