package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.checkpoint.savers.redis.RedisSaver;
import com.alibaba.cloud.ai.graph.serializer.plain_text.jackson.SpringAIJacksonStateSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.sql.tool.SqlToolContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.redisson.Redisson;
import org.redisson.config.Config;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** 用真实 Redis 验证工具交付后清理以及未交接任务的恢复。 */
@EnabledIfEnvironmentVariable(named = "ERP_SQL_GRAPH_REDIS_TEST", matches = "true")
class SqlToolRedisTest {
    @Test
    void callbackClearsCompletedWorkAndDeliversUnacknowledgedResult() throws Exception {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:16327");
        var redis = Redisson.create(config);
        try {
            var saver = RedisSaver.builder().redisson(redis)
                    .stateSerializer(new SpringAIJacksonStateSerializer(OverAllState::new)).build();
            var graph = TestGraph.create(saver, new TestGraph.Operations() {
                public Map<String, Object> prepare(Map<String, Object> state) { return state; }
                public Map<String, Object> generate(Map<String, Object> state) {
                    var next = new HashMap<>(state);
                    next.put("route", "end");
                    next.put("status", "NEED_INFO");
                    next.put("message", "请提供仓库");
                    return next;
                }
                public Map<String, Object> execute(Map<String, Object> state) { return state; }
                public Map<String, Object> inspect(Map<String, Object> state) {
                    var next = new HashMap<>(state);
                    next.put("status", "FAILED");
                    next.put("route", "end");
                    next.put("message", state.get("supplements").toString());
                    return next;
                }
            });
            long conversationId = Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong(1000, Long.MAX_VALUE));
            long otherConversationId = conversationId + 1;
            var conversations = mock(AiConversationMapper.class);
            when(conversations.selectOwned(conversationId, 15L)).thenReturn(new AiConversation());
            when(conversations.selectOwned(otherConversationId, 15L)).thenReturn(new AiConversation());
            var user = new LoginUser();
            user.setUserId(15L);
            user.setPermissionCodes(java.util.List.of("ai:assistant:use"));
            var users = mock(LoginUserService.class);
            when(users.findByUserId(15L)).thenReturn(user);
            var service = new SqlGraphService(graph, saver, conversations, redis, users, mock(DatasetManager.class));
            var callback = MethodToolCallbackProvider.builder().toolObjects(new SqlQueryTool(service)).build().getToolCallbacks()[0];
            var context = new ToolContext(Map.of(SqlQueryTool.CONTEXT_KEY, new SqlToolContext(15, conversationId)));
            var otherContext = new ToolContext(Map.of(SqlQueryTool.CONTEXT_KEY, new SqlToolContext(15, otherConversationId)));
            var json = new ObjectMapper();
            var initial = json.readTree(callback.call("{\"requirement\":{\"goal\":\"库存\",\"dataRequirement\":\"FULL\"}}", context));
            assertThat(initial.path("status").asText()).isEqualTo("NEED_INFO");
            String runId = initial.path("sqlRunId").asText();
            var other = json.readTree(callback.call("{\"requirement\":{\"goal\":\"库存\",\"dataRequirement\":\"FULL\"}}", otherContext));
            assertThat(other.path("status").asText()).isEqualTo("NEED_INFO");
            assertThat(other.path("sqlRunId").asText()).isNotEqualTo(runId);
            var next = json.readTree(callback.call("{\"requirement\":{\"goal\":\"仓库清单\",\"dataRequirement\":\"FULL\"}}", context));
            assertThat(next.path("status").asText()).isEqualTo("NEED_INFO");
            assertThat(next.path("sqlRunId").asText()).isNotEqualTo(runId);
            assertThat(next.has("existing")).isFalse();

            // 模拟工具取得结果前进程退出：已结束的检查点仍未被确认。
            var orphan = service.startForUser(15, conversationId, "orphan",
                    Map.of("goal", "原任务", "dataRequirement", "FULL"));
            assertThat(orphan).containsEntry("status", "NEED_INFO");
            user.setPermissionCodes(java.util.List.of());
            var denied = json.readTree(callback.call("{\"requirement\":{\"goal\":\"新任务\",\"dataRequirement\":\"FULL\"}}", context));
            assertThat(denied.path("code").asText()).isEqualTo("AI_PERMISSION_DENIED");
            user.setPermissionCodes(java.util.List.of("ai:assistant:use"));
            var existing = json.readTree(callback.call("{\"requirement\":{\"goal\":\"新任务\",\"dataRequirement\":\"FULL\"}}", context));
            assertThat(existing.path("sqlRunId").asText()).isEqualTo("orphan");
            assertThat(existing.path("existing").asBoolean()).isTrue();
            assertThat(existing.path("newRequestStarted").asBoolean()).isFalse();
            var after = json.readTree(callback.call("{\"requirement\":{\"goal\":\"新任务\",\"dataRequirement\":\"FULL\"}}", context));
            assertThat(after.path("sqlRunId").asText()).isNotEqualTo("orphan");
            assertThat(after.has("existing")).isFalse();
        } finally {
            redis.shutdown();
        }
    }
}
