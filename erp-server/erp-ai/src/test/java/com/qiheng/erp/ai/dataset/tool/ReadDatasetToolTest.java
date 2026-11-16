package com.qiheng.erp.ai.dataset.tool;

import com.qiheng.erp.ai.sql.tool.SqlQueryTool;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;

import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetPage;
import com.qiheng.erp.ai.dataset.DatasetUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证框架工具隐藏身份并使用可信上下文。 */
class ReadDatasetToolTest {
    @Test
    void unavailableDatasetExplainsHowToContinue() {
        var manager = mock(DatasetManager.class);
        when(manager.readPage("ds_old", 1, 2, 0, 5))
                .thenThrow(new DatasetUnavailableException("数据集已过期"));
        var callback = MethodToolCallbackProvider.builder().toolObjects(new ReadDatasetTool(manager))
                .build().getToolCallbacks()[0];
        var context = new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, new AgentToolContext(1, 2)));
        assertThat(callback.call("{\"datasetId\":\"ds_old\",\"limit\":5}", context))
                .contains("UNAVAILABLE", "不要重复读取", "重新查询");
    }

    @Test
    void callbackReadsPageWithTrustedIdentity() {
        var manager = mock(DatasetManager.class);
        when(manager.readPage("ds_a", 1, 2, 0, 5)).thenReturn(new DatasetPage(List.of(), List.of(List.of(12)), 1, 1, false));
        var callback = MethodToolCallbackProvider.builder().toolObjects(new ReadDatasetTool(manager)).build().getToolCallbacks()[0];
        assertThat(callback.getToolDefinition().inputSchema()).doesNotContain("userId", "conversationId", "toolContext");
        var context = new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, new AgentToolContext(1, 2)));
        assertThat(callback.call("{\"datasetId\":\"ds_a\",\"offset\":0,\"limit\":5}", context)).contains("12", "returnedRows");
        when(manager.readPage("ds_a", 1, 2, 0, Integer.MAX_VALUE)).thenReturn(new DatasetPage(List.of(), List.of(List.of(12)), 1, 1, false));
        assertThat(callback.call("{\"datasetId\":\"ds_a\"}", context)).contains("12", "truncated", "nextOffset");
        assertThatThrownBy(() -> callback.call("{\"datasetId\":\"ds_a\",\"offset\":0,\"limit\":5}", new ToolContext(Map.of("userId", 1))))
                .hasRootCauseMessage("缺少可信 SQL 工具上下文");
        verify(manager).readPage("ds_a", 1, 2, 0, 5);
    }
}
