package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.sql.tool.SqlToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 测试专用拦截器从可信运行配置注入身份，验证真实 Agent 工具调用的隔离。 */
class MainAgentToolContextTest {
    @Test
    void passesPerRunIdentityToSqlToolAcrossConcurrentAgentRuns() throws Exception {
        SqlGraphService service = mock(SqlGraphService.class);
        when(service.startForUserAtDate(anyLong(), anyLong(), anyString(), anyMap(), anyString()))
                .thenAnswer(call -> Map.of("status", "SUCCESS", "sqlRunId", call.getArgument(2)));
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            boolean returned = prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance);
            AssistantMessage message = returned ? new AssistantMessage("已取得数据。")
                    : AssistantMessage.builder().content("先查询库存，确认商品数量。")
                    .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", "query_sql_data",
                            "{\"requirement\":{\"goal\":\"库存\",\"dataRequirement\":\"FULL\"}}"))).build();
            return Flux.just(new ChatResponse(List.of(new Generation(message))));
        });
        ToolInterceptor identity = new ToolInterceptor() {
            @Override
            public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
                var run = request.getExecutionContext().orElseThrow().config();
                var trusted = (SqlToolContext) run.metadata("trustedSqlContext").orElseThrow();
                return handler.call(ToolCallRequest.builder(request)
                        .context(Map.of(SqlQueryTool.CONTEXT_KEY, trusted)).build());
            }
            @Override public String getName() { return "test_identity"; }
        };
        var agent = ReactAgent.builder().name("tool-context-test").model(model)
                .methodTools(new SqlQueryTool(service)).interceptors(identity).build();
        var one = RunnableConfig.builder().threadId("user-1")
                .addMetadata("trustedSqlContext", new SqlToolContext(1, 101, "2026-11-05")).build();
        var two = RunnableConfig.builder().threadId("user-2")
                .addMetadata("trustedSqlContext", new SqlToolContext(2, 202, "2026-11-06")).build();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> agent.stream("库存", one).collectList().block(Duration.ofSeconds(15)));
            var second = executor.submit(() -> agent.stream("库存", two).collectList().block(Duration.ofSeconds(15)));
            assertThat(first.get()).isNotEmpty();
            assertThat(second.get()).isNotEmpty();
        }
        verify(service).startForUserAtDate(eq(1L), eq(101L), anyString(), anyMap(), eq("2026-11-05"));
        verify(service).startForUserAtDate(eq(2L), eq(202L), anyString(), anyMap(), eq("2026-11-06"));
    }
}
