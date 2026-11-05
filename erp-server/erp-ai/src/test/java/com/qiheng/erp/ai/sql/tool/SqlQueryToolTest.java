package com.qiheng.erp.ai.sql.tool;

import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.sql.tool.SqlToolContext;

import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用框架回调验证工具协议和可信上下文注入。 */
class SqlQueryToolTest {
    @Test
    void callbackUsesTrustedContextAcrossThreadsAndKeepsIdentityOutOfSchema() throws Exception {
        SqlGraphService service = mock(SqlGraphService.class);
        when(service.startForUser(eq(15L), eq(1001L), anyString(), anyMap()))
                .thenReturn(Map.of("sqlRunId", "run1", "status", "NEED_INFO", "message", "哪个仓库？"));
        var callback = MethodToolCallbackProvider.builder().toolObjects(new SqlQueryTool(service)).build().getToolCallbacks()[0];
        assertThat(callback.getToolDefinition().inputSchema()).doesNotContain("userId", "conversationId", "toolContext", "sqlRunId");
        assertThat(callback.getToolDefinition().inputSchema()).contains("goal", "dataRequirement", "grain", "expectedFields", "purpose", "FULL", "LIMITED");
        var schema = new ObjectMapper().readTree(callback.getToolDefinition().inputSchema());
        var requirement = schema.path("properties").path("requirement");
        assertThat(requirement.path("properties").path("expectedFields").path("type").asText()).isEqualTo("array");
        assertThat(requirement.path("properties").path("goal").path("description").asText()).contains("查询目标");
        assertThat(requirement.path("properties").path("dataRequirement").path("description").asText()).contains("FULL", "LIMITED");
        assertThat(requirement.path("required").toString()).contains("goal", "dataRequirement").doesNotContain("grain", "purpose");
        ToolContext context = new ToolContext(Map.of(SqlQueryTool.CONTEXT_KEY, new SqlToolContext(15, 1001)));
        try (var executor = Executors.newSingleThreadExecutor()) {
            String result = executor.submit(() -> callback.call("{\"requirement\":{\"goal\":\"库存\",\"dataRequirement\":\"FULL\"}}", context)).get();
            assertThat(result).contains("NEED_INFO", "run1");
        }
        verify(service).acknowledgeForUser(15L, 1001L, "run1");
    }

    @Test
    void partialResultIsDeliveredAndAcknowledged() throws Exception {
        SqlGraphService service = mock(SqlGraphService.class);
        when(service.startForUser(eq(15L), eq(1001L), anyString(), anyMap()))
                .thenReturn(Map.of("sqlRunId", "run1", "status", "PARTIAL", "truncated", true));
        var callback = MethodToolCallbackProvider.builder().toolObjects(new SqlQueryTool(service)).build().getToolCallbacks()[0];
        ToolContext context = new ToolContext(Map.of(SqlQueryTool.CONTEXT_KEY, new SqlToolContext(15, 1001)));
        String result = callback.call("{\"requirement\":{\"goal\":\"库存\",\"dataRequirement\":\"FULL\"}}", context);
        assertThat(result).contains("PARTIAL", "truncated");
        verify(service).acknowledgeForUser(15L, 1001L, "run1");
    }

    @Test
    void rejectsMissingContextAndInvalidInputs() {
        SqlGraphService service = mock(SqlGraphService.class);
        var callback = MethodToolCallbackProvider.builder().toolObjects(new SqlQueryTool(service)).build().getToolCallbacks()[0];
        assertThatThrownBy(() -> callback.call("{\"requirement\":{\"goal\":\"库存\",\"dataRequirement\":\"FULL\"},\"userId\":15}", new ToolContext(Map.of("userId", 15L))))
                .hasRootCauseMessage("缺少可信 SQL 工具上下文");
        ToolContext context = new ToolContext(Map.of(SqlQueryTool.CONTEXT_KEY, new SqlToolContext(15, 1001)));
        assertThatThrownBy(() -> callback.call("{}", context)).hasRootCauseMessage("请提供取数需求");
        verifyNoInteractions(service);
    }
}
