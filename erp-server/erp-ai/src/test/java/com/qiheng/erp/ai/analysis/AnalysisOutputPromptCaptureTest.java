package com.qiheng.erp.ai.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.interceptor.BusinessGraphInterceptor;
import com.qiheng.erp.ai.agent.interceptor.ToolJsonInterceptor;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.python.sandbox.PythonSandboxRuntime;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.python.tool.PythonAnalysisTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;

/** 捕获分析智能体实际发给模型的消息，核对输出类型如何进入提示词。 */
class AnalysisOutputPromptCaptureTest {
    @Test
    void capturesActualPrompt() throws Exception {
        var model = mock(ChatModel.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            for (var message : prompt.getInstructions()) {
                System.out.println("CAPTURE_MESSAGE_START=" + message.getMessageType());
                System.out.println(message.getText());
                System.out.println("CAPTURE_MESSAGE_END");
            }
            var message = calls.getAndIncrement() == 0
                    ? AssistantMessage.builder().content("先查询库存").toolCalls(List.of(
                            new AssistantMessage.ToolCall("sql", "function", "query_sql_data", "{"))).build()
                    : new AssistantMessage("{\"completion\":\"COMPLETED\",\"summary\":\"完成\"}");
            return new ChatResponse(List.of(new Generation(message)));
        });
        var graph = mock(BusinessGraphProvider.class);
        when(graph.render()).thenReturn("【测试业务图谱】仓库关联库存");
        var mapper = new ObjectMapper();
        String prompt = new ClassPathResource("prompts/analysis-agent.md").getContentAsString(StandardCharsets.UTF_8);
        var service = new AnalysisAgentService(model, mock(PythonAnalysisTool.class), mock(PythonSandboxRuntime.class),
                prompt, mock(AiAgentTraceMapper.class), mapper,
                new com.qiheng.erp.ai.sql.tool.SqlQueryTool(mock(com.qiheng.erp.ai.sql.graph.SqlGraphService.class)),
                new com.qiheng.erp.ai.dataset.tool.ReadDatasetTool(mock(com.qiheng.erp.ai.dataset.DatasetManager.class)),
                new ToolJsonInterceptor(mapper), mock(com.qiheng.erp.ai.dataset.tool.ListDatasetsTool.class),
                mock(AnalysisResultTool.class), null, null, new BusinessGraphInterceptor(graph));
        assertThat(service.analyze("查询库存", new AgentToolContext(1, 2, "2026-11-09"))).isEqualTo("完成");
        assertThat(calls).hasValue(2);
    }
}
