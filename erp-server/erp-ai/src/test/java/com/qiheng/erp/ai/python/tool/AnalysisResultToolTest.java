package com.qiheng.erp.ai.python.tool;

import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.domain.entity.AiAnalysisResult;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.dataset.DatasetUnavailableException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnalysisResultToolTest {
    @Test
    void explainsUnavailableSourceDataset() throws Exception {
        var identity = new AgentToolContext(10, 20);
        var context = new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, identity));
        var store = mock(AnalysisResultStore.class);
        when(store.readText("result-old", identity)).thenThrow(new DatasetUnavailableException("数据集已过期"));
        var result = new AnalysisResultTool(store).readAnalysisResult("result-old", context);
        assertEquals("UNAVAILABLE", result.get("status"));
        assertTrue(result.get("guidance").toString().contains("重新查询"));
    }

    /** 结果列表使用可信身份和当前分析任务，仅返回元信息。 */
    @Test
    void listsOnlyCurrentTask() throws Exception {
        var identity = new AgentToolContext(10, 20);
        var context = new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, identity,
                AnalysisResultTool.CONTEXT_TASK_ID, "task-1"));
        var store = mock(AnalysisResultStore.class);
        var record = new AiAnalysisResult();
        record.setResultId("result-1");
        record.setName("结果.csv");
        record.setFileType("csv");
        when(store.list(identity, "task-1")).thenReturn(List.of(record));

        var response = new AnalysisResultTool(store).listAnalysisResults(null, context);
        assertEquals("task-1", response.get("analysisTaskId"));
        assertEquals("result-1", ((Map<?, ?>) ((List<?>) response.get("results")).getFirst()).get("resultId"));
        verify(store).list(identity, "task-1");
    }

    /** 服务器上的文本按结果 ID 完整读取，不需要沙箱。 */
    @Test
    void readsWholeResult() throws Exception {
        var identity = new AgentToolContext(10, 20);
        var context = new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, identity));
        var store = mock(AnalysisResultStore.class);
        String content = "完整结果".repeat(3000);
        when(store.readText("result-1", identity)).thenReturn(new AnalysisResultStore.TextResult("结果.txt", "txt", content));
        assertEquals(content, new AnalysisResultTool(store).readAnalysisResult("result-1", context).get("content"));
    }

    /** 缺少可信身份或来源权限被撤销时不返回内容。 */
    @Test
    void refusesUnauthorizedReads() throws Exception {
        var store = mock(AnalysisResultStore.class);
        var tool = new AnalysisResultTool(store);
        assertThrows(IllegalStateException.class, () -> tool.readAnalysisResult("result-1", new ToolContext(Map.of())));
        var identity = new AgentToolContext(11, 22);
        var context = new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, identity));
        when(store.readText("result-1", identity)).thenThrow(new IllegalArgumentException("分析结果不存在"));
        assertThrows(IllegalArgumentException.class, () -> tool.readAnalysisResult("result-1", context));
    }
}
