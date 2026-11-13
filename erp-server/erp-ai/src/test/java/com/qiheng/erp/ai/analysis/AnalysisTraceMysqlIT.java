package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.annotation.Tool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.qiheng.erp.ai.trace.AiAgentTraceRecorder;

/** 显式指定测试库时，验证同步 Agent 流水实际写入 MySQL。 */
@EnabledIfEnvironmentVariable(named = "DATASET_IT_URL", matches = ".+")
class AnalysisTraceMysqlIT {
    public static class ProbeTools {
        @Tool(name = "first_probe", description = "第一个测试工具")
        public String first() {
            return "one";
        }

        @Tool(name = "second_probe", description = "第二个测试工具")
        public String second() {
            return "two";
        }
    }

    /** 在一个助手消息中返回文字和两个工具调用，验证全链路持久化。 */
    @Test
    void storesTextCallsAndResponsesInOrder() throws Exception {
        String url = System.getenv("DATASET_IT_URL");
        String user = System.getenv("DATASET_IT_USER");
        String password = System.getenv("DATASET_IT_PASSWORD");
        String taskId = "trace_it_" + UUID.randomUUID();
        var dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver", url, user, password);
        String schema = Files.readString(Path.of("..", "..", "docs", "database", "sql", "035_ai_agent_trace.sql"));
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute(schema);
        }

        var configuration = new Configuration(new Environment("trace-it", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(AiAgentTraceMapper.class);
        var factory = new SqlSessionFactoryBuilder().build(configuration);
        var mapper = new ObjectMapper();
        try (var session = factory.openSession(true)) {
            var recorder = new AiAgentTraceRecorder(session.getMapper(AiAgentTraceMapper.class), mapper,
                    new AiAgentTraceRecorder.Scope(91001, 92001, taskId, "ANALYSIS"));
            var model = mock(ChatModel.class);
            var count = new AtomicInteger();
            when(model.call(any(Prompt.class))).thenAnswer(call -> {
                if (count.getAndIncrement() > 0) {
                    return new ChatResponse(List.of(new Generation(new AssistantMessage("完成"))));
                }
                var calls = List.of(new AssistantMessage.ToolCall("a", "function", "first_probe", "{}"),
                        new AssistantMessage.ToolCall("b", "function", "second_probe", "{}"));
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .content("先检查数据").toolCalls(calls).build())));
            });
            var agent = ReactAgent.builder().name("trace_mysql_it").model(model)
                    .parallelToolExecution(false).methodTools(new ProbeTools())
                    .hooks(new AnalysisTraceHook()).build();
            try {
                recorder.record("INPUT", "开始");
                String answer = agent.call("开始", RunnableConfig.builder().threadId(taskId)
                        .addMetadata(AnalysisTraceHook.RECORDER_KEY, recorder).build()).getText();
                recorder.record("FINAL", answer);
                assertThat(answer).isEqualTo("完成");

                var types = new ArrayList<String>();
                var contents = new ArrayList<String>();
                try (var connection = dataSource.getConnection();
                     var query = connection.prepareStatement(
                             "SELECT event_type, content FROM ai_agent_trace WHERE task_id = ? ORDER BY id")) {
                    query.setString(1, taskId);
                    try (var rows = query.executeQuery()) {
                        while (rows.next()) {
                            types.add(rows.getString(1));
                            contents.add(rows.getString(2));
                        }
                    }
                }
                assertThat(types).containsExactly("INPUT", "MODEL_OUTPUT", "TOOL_CALL", "TOOL_CALL",
                        "TOOL_RESULT", "TOOL_RESULT", "FINAL");
                assertThat(mapper.readTree(contents.get(1)).path("text").asText()).isEqualTo("先检查数据");
                assertThat(mapper.readTree(contents.get(2)).path("callId").asText()).isEqualTo("a");
                assertThat(mapper.readTree(contents.get(3)).path("callId").asText()).isEqualTo("b");
                assertThat(mapper.readTree(contents.get(4)).path("text").asText()).contains("one");
                assertThat(mapper.readTree(contents.get(5)).path("text").asText()).contains("two");
            } finally {
                try (var connection = dataSource.getConnection();
                     var delete = connection.prepareStatement("DELETE FROM ai_agent_trace WHERE task_id = ?")) {
                    delete.setString(1, taskId);
                    delete.executeUpdate();
                }
            }
        }
    }
}
