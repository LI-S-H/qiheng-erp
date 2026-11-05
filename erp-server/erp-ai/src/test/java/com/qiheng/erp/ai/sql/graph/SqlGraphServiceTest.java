package com.qiheng.erp.ai.sql.graph;

import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.security.context.UserContext;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SqlGraphServiceTest {
    @Test
    void interruptedInspectionKeepsStagedUntilCheckpointReachesEnd() throws Exception {
        var saver = new MemorySaver();
        AtomicInteger attempts = new AtomicInteger();
        var graph = TestGraph.create(saver, new TestGraph.Operations() {
            public Map<String, Object> prepare(Map<String, Object> state) { return state; }
            public Map<String, Object> generate(Map<String, Object> state) { return state; }
            public Map<String, Object> execute(Map<String, Object> state) {
                var next = new HashMap<>(state);
                next.put("datasetId", "ds-staged");
                return next;
            }
            public Map<String, Object> inspect(Map<String, Object> state) {
                if (attempts.getAndIncrement() == 0) throw new IllegalStateException("模拟检查中断");
                var next = new HashMap<>(state);
                next.remove("datasetId");
                next.put("status", "FAILED");
                next.put("route", "end");
                return next;
            }
        });
        var mapper = mock(AiConversationMapper.class);
        when(mapper.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var redis = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        var user = new LoginUser();
        user.setUserId(1L);
        user.setPermissionCodes(java.util.List.of("ai:assistant:use"));
        var users = mock(LoginUserService.class);
        when(users.findByUserId(1L)).thenReturn(user);
        var datasets = mock(DatasetManager.class);
        var service = new SqlGraphService(graph, saver, mapper, redis, users, datasets);
        try (var context = mockStatic(UserContext.class)) {
            context.when(UserContext::requireCurrentUser).thenReturn(user);
            assertThatThrownBy(() -> service.startForCurrentUser(2, "run1",
                    Map.of("goal", "库存", "dataRequirement", "FULL"))).hasMessageContaining("模拟检查中断");
            verify(datasets, never()).cleanupStaged(anyString(), anyLong(), anyLong());
            assertThat(service.recoverForCurrentUser(2, "run1")).containsEntry("status", "FAILED").doesNotContainKey("datasetId");
            verify(datasets).cleanupStaged("run1", 1L, 2L);
        }
    }

    @Test
    void resultsMustBeAcknowledgedBeforeTheSessionCanBeReused() throws Exception {
        var saver = new MemorySaver();
        var graph = TestGraph.create(saver, new TestGraph.Operations() {
            public Map<String, Object> prepare(Map<String, Object> state) { return state; }
            public Map<String, Object> generate(Map<String, Object> state) { return state; }
            public Map<String, Object> execute(Map<String, Object> state) { return state; }
            public Map<String, Object> inspect(Map<String, Object> state) {
                var next = new HashMap<>(state);
                next.put("datasetId", "ds-" + state.get("sqlRunId"));
                next.put("route", "end");
                return next;
            }
        });
        var mapper = mock(AiConversationMapper.class);
        when(mapper.selectOwned(2L, 1L)).thenReturn(new AiConversation());
        var redis = mock(RedissonClient.class);
        var lock = mock(RLock.class);
        when(redis.getLock(anyString())).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        var user = new LoginUser();
        user.setUserId(1L);
        user.setPermissionCodes(java.util.List.of("ai:assistant:use"));
        var users = mock(LoginUserService.class);
        when(users.findByUserId(1L)).thenReturn(user);
        var datasets = mock(DatasetManager.class);
        doThrow(new IllegalStateException("模拟清理失败")).doNothing()
                .when(datasets).cleanupStaged("run1", 1L, 2L);
        var service = new SqlGraphService(graph, saver, mapper, redis, users, datasets);
        try (var context = mockStatic(UserContext.class)) {
            context.when(UserContext::requireCurrentUser).thenReturn(user);
            assertThat(service.startForCurrentUser(2, "run1", Map.of("goal", "库存", "dataRequirement", "FULL")).get("datasetId"))
                    .isEqualTo("ds-run1");
            assertThat(service.startForCurrentUser(2, "run2", Map.of("goal", "出库", "dataRequirement", "FULL")))
                    .containsEntry("existing", true).containsEntry("sqlRunId", "run1");
            assertThat(service.recoverForCurrentUser(2, "run1").get("datasetId")).isEqualTo("ds-run1");
            verify(datasets, times(2)).cleanupStaged("run1", 1L, 2L);
            doThrow(new IllegalStateException("清理失败")).doNothing()
                    .when(datasets).cleanupStaged("run1", 1L, 2L);
            assertThatThrownBy(() -> service.acknowledgeForCurrentUser(2, "run1")).hasMessageContaining("清理失败");
            assertThat(service.startForCurrentUser(2, "run2", Map.of("goal", "出库", "dataRequirement", "FULL")))
                    .containsEntry("existing", true).containsEntry("sqlRunId", "run1");
            service.acknowledgeForCurrentUser(2, "run1");
            assertThat(service.startForCurrentUser(2, "run2", Map.of("goal", "出库", "dataRequirement", "FULL")).get("datasetId"))
                    .isEqualTo("ds-run2");
            assertThatThrownBy(() -> service.recoverForCurrentUser(3, "run2")).hasMessageContaining("无权");
        }
    }
}
