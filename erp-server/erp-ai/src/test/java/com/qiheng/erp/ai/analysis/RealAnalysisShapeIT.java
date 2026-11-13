package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.domain.entity.AiAnalysisResult;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.python.sandbox.PythonSandboxRuntime;
import com.qiheng.erp.ai.python.tool.PythonAnalysisTool;
import com.qiheng.erp.ai.python.tool.PythonDatasetPreparer;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;

/** 使用真实模型和 Python 沙箱观察同步分析任务中的消息形态。 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_ANALYSIS_SHAPE", matches = "true")
class RealAnalysisShapeIT {
    @HookPositions({HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
    static class Capture extends MessagesModelHook {
        final List<List<Message>> before = new ArrayList<>();
        final List<List<Message>> after = new ArrayList<>();
        @Override
        public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
            before.add(List.copyOf(messages));
            return new AgentCommand(null);
        }
        @Override
        public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
            after.add(List.copyOf(messages));
            return new AgentCommand(null);
        }
        @Override
        public String getName() {
            return "real_analysis_shape";
        }
    }

    @Test
    void capturesMultipleRealTasks() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var props = Objects.requireNonNull(yaml.getObject());
        String thinking = props.getProperty("erp.ai.model.enable-thinking");
        Boolean enabled = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        var model = OpenAiCompatibleChatModel.create(props.getProperty("erp.ai.api-key"),
                props.getProperty("erp.ai.base-url"), props.getProperty("erp.ai.model.pro"),
                0.2, Duration.ofSeconds(90), enabled);
        String prompt = new ClassPathResource("prompts/analysis-agent.md").getContentAsString(StandardCharsets.UTF_8);
        var mapper = new ObjectMapper();
        var logger = (Logger) LoggerFactory.getLogger("io.agentscope.runtime.sandbox.manager.SandboxService");
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.WARN);
        var runtime = new PythonSandboxRuntime(mapper, "erp-ai-sandbox-probe:local");
        var goals = List.of(
                "请先简短说明，再用 executePython 计算 2、4、6 的平均数，最后报告结果。",
                "请依次调用 listAnalysisResults 和 executePython 计算 7*8；每次调用前简短说明。",
                "请先用 executePython 运行 raise ValueError('probe_error')，收到错误后再调用 executePython 计算 3+5，报告结果。"
        );
        try {
            for (int n = 0; n < goals.size(); n++) {
                var capture = new Capture();
                var traces = mock(AiAgentTraceMapper.class);
                var identity = new AgentToolContext(91001, 92001);
                String taskId = UUID.randomUUID().toString();
                var recorder = new AiAgentTraceRecorder(traces, mapper, new AiAgentTraceRecorder.Scope(identity.userId(), identity.conversationId(), taskId, "ANALYSIS"));
                var store = mock(AnalysisResultStore.class);
                when(store.list(any(), anyString())).thenReturn(List.of());
                when(store.save(any(), anyString(), anyString(), anyString(), anyString(), nullable(String.class),
                        nullable(String.class), anyList(), any())).thenAnswer(invocation -> {
                            var result = new AiAnalysisResult();
                            result.setResultId(UUID.randomUUID().toString());
                            return result;
                        });
                try (var task = new AnalysisTaskContext(identity, taskId, runtime)) {
                    var tool = new PythonAnalysisTool(new PythonDatasetPreparer(mock(DatasetManager.class)), store);
                    var agent = ReactAgent.builder().name("real_shape_" + n).model(model)
                            .systemPrompt(prompt).methodTools(tool, new AnalysisResultTool(store)).parallelToolExecution(false)
                            .interceptors(new TrustedToolContextInterceptor())
                            .hooks(capture, new AnalysisTraceHook()).build();
                    var config = RunnableConfig.builder()
                            .addMetadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT, identity)
                            .addMetadata(AnalysisTaskContext.CONTEXT_KEY, task)
                            .addMetadata(AnalysisTraceHook.RECORDER_KEY, recorder)
                            .addMetadata(AnalysisResultTool.CONTEXT_TASK_ID, taskId).build();
                    String result = agent.call(goals.get(n), config).getText();
                    assertThat(result).isNotBlank();
                    for (int i = 0; i < capture.after.size(); i++) {
                        var messages = capture.after.get(i);
                        var last = messages.getLast();
                        if (last instanceof AssistantMessage assistant) {
                            System.out.println("SHAPE task=" + n + " stage=after round=" + i
                                    + " text=" + (assistant.getText() != null && !assistant.getText().isBlank())
                                    + " callIds=" + assistant.getToolCalls().stream().map(c -> c.name() + ":" + c.id()).toList());
                        }
                    }
                    var observedIds = new ArrayList<String>();
                    int previousSize = 0;
                    for (int i = 0; i < capture.before.size(); i++) {
                        var messages = capture.before.get(i);
                        var last = messages.getLast();
                        var newlyAdded = messages.subList(Math.min(previousSize, messages.size()), messages.size());
                        var shapes = newlyAdded.stream().filter(ToolResponseMessage.class::isInstance)
                                .map(ToolResponseMessage.class::cast)
                                .map(x -> x.getResponses().stream().map(v -> v.name() + ":" + v.id()).toList()).toList();
                        newlyAdded.stream().filter(ToolResponseMessage.class::isInstance).map(ToolResponseMessage.class::cast)
                                .flatMap(v -> v.getResponses().stream()).map(ToolResponseMessage.ToolResponse::id)
                                .forEach(observedIds::add);
                        System.out.println("SHAPE task=" + n + " stage=before round=" + i
                                + " last=" + last.getClass().getSimpleName() + " newResponseGroups=" + shapes);
                        previousSize = messages.size();
                    }
                    var events = org.mockito.ArgumentCaptor.forClass(String.class);
                    var contents = org.mockito.ArgumentCaptor.forClass(String.class);
                    verify(traces, atLeastOnce()).insert(anyLong(), anyLong(), anyString(), anyString(), events.capture(), contents.capture());
                    var savedIds = new ArrayList<String>();
                    for (int i = 0; i < events.getAllValues().size(); i++) {
                        if ("TOOL_RESULT".equals(events.getAllValues().get(i))) {
                            savedIds.add(mapper.readTree(contents.getAllValues().get(i)).path("callId").asText());
                        }
                    }
                    System.out.println("SHAPE task=" + n + " stored=" + events.getAllValues());
                    System.out.println("SHAPE task=" + n + " observedResponseIds=" + observedIds + " savedResponseIds=" + savedIds);
                    assertThat(savedIds).containsExactlyElementsOf(observedIds);
                }
            }
        } finally {
            runtime.close();
            logger.setLevel(previousLevel);
        }
    }
}
