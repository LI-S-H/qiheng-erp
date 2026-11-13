package com.qiheng.erp.ai.agent;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.HookPosition;
import com.alibaba.cloud.ai.graph.agent.hook.HookPositions;
import com.alibaba.cloud.ai.graph.agent.hook.messages.AgentCommand;
import com.alibaba.cloud.ai.graph.agent.hook.messages.MessagesModelHook;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.openai.OpenAiChatOptions;
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

/** 真实主模型、主提示词和模拟数据工具的消息形态探针，不访问业务数据。 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_MAIN_SHAPE_IT", matches = "true")
class RealMainMessageShapeIT {
    public static class ProbeTools {
        @Tool(name = "query_sql_data", description = "查询模拟业务数据，输入查询目标。")
        public String query(String goal) {
            return "模拟数据：商品A库存8件，商品B库存5件";
        }

        @Tool(name = "readDataset", description = "读取模拟数据集的内容。")
        public String read(String datasetId) {
            return "模拟数据集：A=8件，B=5件";
        }
    }

    @HookPositions({HookPosition.BEFORE_MODEL, HookPosition.AFTER_MODEL})
    static class Capture extends MessagesModelHook {
        final List<String> lines = new ArrayList<>();
        int beforeCount;
        int afterCount;
        int previousSize;

        @Override
        public AgentCommand beforeModel(List<Message> messages, RunnableConfig config) {
            beforeCount++;
            var types = new ArrayList<String>();
            for (Message message : messages) {
                if (message instanceof ToolResponseMessage result) {
                    types.add("ToolResponse(" + result.getResponses().stream().map(item -> item.id()).toList() + ")");
                } else {
                    types.add(message.getClass().getSimpleName());
                }
            }
            lines.add("before=" + beforeCount + " messages=" + types);
            var allNewIds = new ArrayList<String>();
            var newResponseSizes = new ArrayList<Integer>();
            for (int i = Math.min(previousSize, messages.size()); i < messages.size(); i++) {
                if (messages.get(i) instanceof ToolResponseMessage response) {
                    newResponseSizes.add(response.getResponses().size());
                    response.getResponses().forEach(item -> allNewIds.add(item.id()));
                }
            }
            var lastIds = new ArrayList<String>();
            if (!messages.isEmpty() && messages.getLast() instanceof ToolResponseMessage last) {
                last.getResponses().forEach(item -> lastIds.add(item.id()));
            }
            lines.add("newToolMessages=" + newResponseSizes + " allNewIds=" + allNewIds
                    + " lastMessageIds=" + lastIds + " omitted=" + !lastIds.containsAll(allNewIds));
            previousSize = messages.size();
            return new AgentCommand(null);
        }

        @Override
        public AgentCommand afterModel(List<Message> messages, RunnableConfig config) {
            afterCount++;
            if (!messages.isEmpty() && messages.getLast() instanceof AssistantMessage assistant) {
                lines.add("after=" + afterCount + " textPresent=" + (assistant.getText() != null && !assistant.getText().isBlank())
                        + " calls=" + assistant.getToolCalls().stream().map(call -> call.id() + ":" + call.name()).toList());
            }
            return new AgentCommand(null);
        }

        @Override
        public String getName() {
            return "main_shape_probe";
        }
    }

    /** 以真实模型运行多个问题，观测框架交给两个 Hook 的消息。 */
    @Test
    void samplesMainAgentMessageShape() throws Exception {
        sampleMainAgentMessageShape(false);
    }

    /** 使用生产模型的 parallelToolCalls(false) 设置再做一轮真实模型观察。 */
    @Test
    void samplesProductionSerialModelMessageShape() throws Exception {
        sampleMainAgentMessageShape(true);
    }

    private void sampleMainAgentMessageShape(boolean serialModel) throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        String thinking = settings.getProperty("erp.ai.model.enable-thinking");
        Boolean enabled = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        var baseModel = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.pro"),
                0.2, Duration.ofSeconds(90), enabled);
        var options = ((OpenAiChatOptions) baseModel.getDefaultOptions()).copy();
        if (serialModel) {
            options.setParallelToolCalls(false);
        }
        var model = baseModel.mutate().defaultOptions(options).build();
        String prompt = new ClassPathResource("prompts/main-agent.md").getContentAsString(StandardCharsets.UTF_8);
        var goals = List.of(
                "请查询模拟库存数据并回答商品数量，务必调用 query_sql_data。",
                "请先调用 query_sql_data 查询库存，再调用 readDataset 读取模拟数据集 ds1，最后说明结果。",
                "请分别查询商品A和商品B的模拟库存，各调用一次 query_sql_data，然后汇总。");
        for (int i = 0; i < goals.size(); i++) {
            var capture = new Capture();
            var agent = ReactAgent.builder().name("main_shape_" + i).model(model).systemPrompt(prompt)
                    .methodTools(new ProbeTools()).hooks(capture).parallelToolExecution(false).build();
            agent.stream(goals.get(i), RunnableConfig.builder().threadId(UUID.randomUUID().toString()).build())
                    .collectList().block(Duration.ofMinutes(3));
            System.out.println("MAIN_SHAPE_CASE=" + (i + 1) + " serialModel=" + serialModel);
            capture.lines.forEach(System.out::println);
            assertThat(capture.beforeCount).isGreaterThan(0);
        }
    }
}
