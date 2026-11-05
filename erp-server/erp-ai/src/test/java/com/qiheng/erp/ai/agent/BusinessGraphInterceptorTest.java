package com.qiheng.erp.ai.agent;

import com.qiheng.erp.ai.agent.interceptor.BusinessGraphInterceptor;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.sql.graph.SqlGraphService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BusinessGraphInterceptorTest {
    @Test void graphIsInjectedOnceAcrossToolCalls() throws Exception {
        var graph = mock(BusinessGraphProvider.class);
        String marker = "【ERP 业务实体关系参考】";
        when(graph.render()).thenReturn(marker + "仓库 → 库存");
        var model = mock(ChatModel.class);
        var calls = new AtomicInteger();
        when(model.stream(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            String all = prompt.getInstructions().toString();
            assertThat(all.indexOf(marker)).isGreaterThanOrEqualTo(0);
            assertThat(all.indexOf(marker)).isEqualTo(all.lastIndexOf(marker));
            assertThat(all).contains("当前业务日期：2026-11-05");
            assertThat(all.indexOf("【本轮时间上下文】")).isEqualTo(all.lastIndexOf("【本轮时间上下文】"));
            int round = calls.incrementAndGet();
            var message = round == 1 ? AssistantMessage.builder().toolCalls(List.of(
                    new AssistantMessage.ToolCall("graph-call", "function", "query_sql_data", "{"))).build()
                    : new AssistantMessage("完成");
            return Flux.just(new ChatResponse(List.of(new Generation(message))));
        });
        var agent = ReactAgent.builder().name("graph-multiturn-test").model(model).systemPrompt("规划任务")
                .methodTools(new SqlQueryTool(mock(SqlGraphService.class)))
                .interceptors(new BusinessGraphInterceptor(graph),
                        new com.qiheng.erp.ai.agent.interceptor.ToolJsonInterceptor(new com.fasterxml.jackson.databind.ObjectMapper()))
                .build();
        var config = com.alibaba.cloud.ai.graph.RunnableConfig.builder().threadId("graph-multiturn").addMetadata("businessDate", "2026-11-05").build();
        assertThat(agent.stream("查询库存", config).collectList().block(Duration.ofSeconds(20))).isNotEmpty();
        assertThat(calls).hasValue(2);
        verify(graph, times(2)).render();
    }
    @Test void streamingAgentReceivesBusinessGraphInSystemMessage() throws Exception {
        var graph = mock(BusinessGraphProvider.class);
        when(graph.render()).thenReturn("【ERP 业务实体关系参考】仓库 → 库存");
        var model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenAnswer(call -> {
            Prompt prompt = call.getArgument(0);
            assertThat(prompt.getInstructions().toString()).contains("【ERP 业务实体关系参考】仓库 → 库存");
            return Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("完成")))));
        });
        var agent = ReactAgent.builder().name("graph-test").model(model).systemPrompt("规划任务")
                .interceptors(new BusinessGraphInterceptor(graph)).build();
        var config = com.alibaba.cloud.ai.graph.RunnableConfig.builder().threadId("graph-test").build();
        assertThat(agent.stream("查询库存", config).collectList().block(Duration.ofSeconds(15))).isNotEmpty();
        verify(graph).render();
    }
}
