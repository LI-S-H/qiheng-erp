package com.qiheng.erp.ai.dataset.tool;

import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.dataset.DatasetPage;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ListDatasetsToolTest {
    /** 工具只使用服务端可信身份，兼容历史数据缺少说明。 */
    @Test
    void listsMetadataUsingTrustedContext() {
        var datasets = mock(DatasetManager.class);
        var record = new DatasetRecord("ds-1", "run", 1, 2, "库存", List.of(), 3,
                true, "READY", null, null);
        when(datasets.listReady(1, 2)).thenReturn(List.of(record));
        when(datasets.readPage("ds-1", 1, 2, 0, 2)).thenReturn(
                new DatasetPage(List.of(), List.of(List.of("商品甲"), List.of("商品乙")), 3, 2, true));
        var callback = MethodToolCallbackProvider.builder().toolObjects(new ListDatasetsTool(datasets))
                .build().getToolCallbacks()[0];
        assertThat(callback.getToolDefinition().name()).isEqualTo("listDatasets");
        assertThat(callback.getToolDefinition().inputSchema()).doesNotContain("userId", "conversationId");
        var context = new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, new AgentToolContext(1, 2)));
        assertThat(callback.call("{}", context)).contains("ds-1", "库存", "completed", "preview", "商品甲", "商品乙", "unavailableCount");
        verify(datasets).listReady(1, 2);
        verify(datasets).readPage("ds-1", 1, 2, 0, 2);
    }
}
