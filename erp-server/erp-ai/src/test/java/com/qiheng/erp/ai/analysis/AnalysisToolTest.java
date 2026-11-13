package com.qiheng.erp.ai.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AnalysisToolTest {
    /** 模型只看到目标和补充条件，身份由服务端提供。 */
    @Test
    void exposesOnlyBusinessParameters() throws Exception {
        var tool = new AnalysisTool(mock(AnalysisAgentService.class));
        var callback = MethodToolCallbackProvider.builder().toolObjects(tool).build().getToolCallbacks()[0];
        var schema = new ObjectMapper().readTree(callback.getToolDefinition().inputSchema());
        assertThat(callback.getToolDefinition().name()).isEqualTo("analyzeData");
        assertThat(schema.path("properties").has("goal")).isTrue();
        assertThat(schema.path("properties").has("context")).isTrue();
        assertThat(schema.path("properties").size()).isEqualTo(2);
        assertThat(schema.path("required").toString()).contains("goal").doesNotContain("context");
    }

    /** 使用可信身份调用服务，不接受缺失的身份上下文。 */
    @Test
    void passesTrustedIdentityToService() {
        var service = mock(AnalysisAgentService.class);
        var tool = new AnalysisTool(service);
        var identity = new AgentToolContext(10, 20);
        tool.analyzeData("分析库存", "一号仓库", new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, identity)));
        verify(service).analyzeForMainAgent(new AnalysisCommand("分析库存", "一号仓库", identity, null));
        assertThatThrownBy(() -> tool.analyzeData("分析库存", null, new ToolContext(Map.of())))
                .isInstanceOf(IllegalStateException.class);
        verifyNoMoreInteractions(service);
    }
}
