package com.qiheng.erp.ai.analysis;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 验证分析期间调用工具与最终交接结构能否同时使用。 */
@EnabledIfEnvironmentVariable(named = "ERP_HANDOFF_PROBE_IT", matches = "true")
class RealAnalysisHandoffProbeIT {
    record Handoff(@JsonPropertyDescription("业务完成状态：COMPLETED、PARTIAL 或 NEED_INFO") String completion,
                   @JsonPropertyDescription("分析结论，未完成时说明具体原因") String summary) {
    }

    static final class WarehouseTool {
        final AtomicInteger calls = new AtomicInteger();

        @Tool(name = "lookupWarehouse", description = "查询指定仓库的编号")
        public String lookupWarehouse(String name) {
            calls.incrementAndGet();
            return "未找到仓库名称与编号的对应关系，需要用户确认。";
        }
    }

    static final class DataTool {
        final AtomicInteger calls = new AtomicInteger();

        @Tool(name = "inventoryCount", description = "读取库存记录条数")
        public String inventoryCount() {
            calls.incrementAndGet();
            return "库存记录共 8 条。";
        }

        @Tool(name = "salesCount", description = "读取销售记录条数")
        public String salesCount() {
            calls.incrementAndGet();
            return "权限不足，无法读取销售记录。";
        }
    }

    /** 人为制造一次错误输出，验证真实模型能在原上下文修复。 */
    @Test
    void repairsFinalOutputInAgentLoop() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        String thinking = settings.getProperty("erp.ai.model.enable-thinking");
        Boolean enabled = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.pro"),
                0.2, Duration.ofSeconds(90), enabled);
        var options = ((org.springframework.ai.openai.OpenAiChatOptions) model.getDefaultOptions()).copy();
        options.setParallelToolCalls(false);
        model = model.mutate().defaultOptions(options).build();
        var replaced = new java.util.concurrent.atomic.AtomicBoolean();
        var corruptFinal = new com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor() {
            @Override
            public com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse interceptModel(
                    com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest request,
                    com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler handler) {
                var response = handler.call(request);
                if (response.getMessage() instanceof org.springframework.ai.chat.messages.AssistantMessage message
                        && message.getToolCalls().isEmpty() && replaced.compareAndSet(false, true)) {
                    System.out.println("REAL_LOOP_ORIGINAL=" + message.getText());
                    return com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse.of(
                            new org.springframework.ai.chat.messages.AssistantMessage("不是 JSON"),
                            response.getChatResponse());
                }
                return response;
            }

            @Override
            public String getName() {
                return "corrupt_final_for_probe";
            }
        };
        var tool = new AnalysisHandoffLoopProbeTest.StockTool();
        var attempts = new AtomicInteger();
        var agent = ReactAgent.builder().name("real_loop_probe").model(model)
                .systemPrompt("你是分析智能体，先查询库存记录数，再按要求交付结果。")
                .methodTools(tool).interceptors(corruptFinal)
                .hooks(new AnalysisHandoffLoopProbeTest.ValidationHook(new java.util.ArrayList<>(), true))
                .outputType(AnalysisHandoff.class).parallelToolExecution(false).build();
        var config = com.alibaba.cloud.ai.graph.RunnableConfig.builder().threadId("real-loop-probe")
                .addMetadata("probeHandoffRetries", attempts).build();

        String text = agent.call("请调用 probe_stock 查询库存记录数并回答。", config).getText();
        var result = new ObjectMapper().readValue(text, AnalysisHandoff.class);
        assertThat(replaced.get()).isTrue();
        assertThat(tool.calls).hasValue(1);
        assertThat(attempts).hasValue(1);
        assertThat(result.completion()).isEqualTo("COMPLETED");
        assertThat(result.summary()).contains("8");
        System.out.println("REAL_LOOP_FINAL=" + text);
        System.out.println("REAL_LOOP_TOOL_CALLS=" + tool.calls + " REPAIRS=" + attempts);
    }

    /** 明确完成的目标应正常交付结论。 */
    @Test
    void returnsCompletedAfterTool() throws Exception {
        runDataScenario("查询库存记录条数并返回结果。", "COMPLETED", 1);
    }

    /** 多工具只有部分成功时，结论应说明缺失。 */
    @org.junit.jupiter.api.RepeatedTest(3)
    void returnsPartialAfterMultipleTools() throws Exception {
        runDataScenario("请先查询库存记录条数，拿到结果后再查询销售记录条数。调用每个工具前先简短说明正在做什么；"
                + "如有一项因权限不足无法完成，说明已完成结果和缺口。", "PARTIAL", 2);
    }

    /** 记录每轮的文字与工具调用，观察最终结构化结果。 */
    private void runDataScenario(String goal, String expected, int minimumCalls) throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        String thinking = settings.getProperty("erp.ai.model.enable-thinking");
        Boolean enabled = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.pro"),
                0.2, Duration.ofSeconds(90), enabled);
        var options = ((org.springframework.ai.openai.OpenAiChatOptions) model.getDefaultOptions()).copy();
        options.setParallelToolCalls(false);
        model = model.mutate().defaultOptions(options).build();
        var tool = new DataTool();
        var observer = new com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor() {
            @Override
            public com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse interceptModel(
                    com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest request,
                    com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler handler) {
                var response = handler.call(request);
                if (response.getMessage() instanceof org.springframework.ai.chat.messages.AssistantMessage message) {
                    System.out.println("SCENARIO_" + expected + "_TURN tools=" + message.getToolCalls().size()
                            + " text=" + message.getText());
                }
                return response;
            }

            @Override
            public String getName() {
                return "scenario_observer";
            }
        };
        var agent = ReactAgent.builder().name("scenario_" + expected.toLowerCase()).model(model)
                .systemPrompt("你是内部分析智能体，根据工具结果完成需求；全部完成为 COMPLETED，"
                        + "部分完成为 PARTIAL，需要用户补充必要条件为 NEED_INFO。summary 说明结论和原因。")
                .methodTools(tool).interceptors(observer).outputType(Handoff.class)
                .parallelToolExecution(false).build();
        String text = agent.call(goal).getText();
        System.out.println("SCENARIO_" + expected + "_FINAL=" + text);
        assertThat(tool.calls.get()).isGreaterThanOrEqualTo(minimumCalls);
        var handoff = new ObjectMapper().readValue(text, Handoff.class);
        assertThat(handoff.completion()).isEqualTo(expected);
        assertThat(handoff.summary()).isNotBlank();
    }

    /** 用真实模型先调用工具，再验证最终输出能否解析。 */
    @Test
    void usesToolThenReturnsStructuredHandoff() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        String thinking = settings.getProperty("erp.ai.model.enable-thinking");
        Boolean enabled = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
        var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.pro"),
                0.2, Duration.ofSeconds(90), enabled);
        var tool = new WarehouseTool();
        var observer = new com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor() {
            @Override
            public com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse interceptModel(
                    com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest request,
                    com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler handler) {
                var response = handler.call(request);
                if (response.getMessage() instanceof org.springframework.ai.chat.messages.AssistantMessage message) {
                    System.out.println("HANDOFF_MODEL_TURN tools=" + message.getToolCalls().size()
                            + " text=" + message.getText());
                }
                return response;
            }

            @Override
            public String getName() {
                return "handoff_probe_observer";
            }
        };
        var agent = ReactAgent.builder().name("handoff_probe").model(model)
                .systemPrompt("你是内部分析智能体。先调用工具查找仓库编号，再根据工具结果返回最终结论；缺少必要信息时不要猜测。")
                .methodTools(tool).interceptors(observer).outputType(Handoff.class).build();
        var answer = agent.call("请分析东仓库存；请先查询东仓的仓库编号。");
        String text = answer.getText();
        System.out.println("HANDOFF_TOOL_CALLS=" + tool.calls.get());
        System.out.println("HANDOFF_FINAL=" + text);
        assertThat(tool.calls).hasValueGreaterThan(0);
        var handoff = new ObjectMapper().readValue(text, Handoff.class);
        assertThat(handoff.completion()).isEqualTo("NEED_INFO");
        assertThat(handoff.summary()).isNotBlank();
    }
}
