package com.qiheng.erp.ai.analysis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.DatasetInput;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.domain.entity.AiAnalysisResult;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.python.sandbox.PythonSandboxRuntime;
import com.qiheng.erp.ai.python.tool.PythonAnalysisTool;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.messages.AssistantMessage;
import com.qiheng.erp.ai.python.tool.PythonDatasetPreparer;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.agent.interceptor.ToolJsonInterceptor;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.mock;

/** 显式启用时，使用真实模型及 Docker 验证独立分析智能体。 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_ANALYSIS_IT", matches = "true")
class RealAnalysisAgentIT {
    /** 关键输入缺失时直接交接缺口，并记录工具使用情况供人工审阅。 */
    @Test
    void reportsMissingInputsWithoutInventingSales() throws Exception {
        try (var fixture = new Fixture()) {
            String output = fixture.service.analyze(
                    "请计算商品 P-17 在 2026 年 9 月的销售总量。当前没有该商品的销售明细、"
                            + "销售汇总或已知销售总量，本次没有查询销售数据的工具。"
                            + "请根据已有条件交接结果；无法计算时说明缺少什么，不要虚构数值。",
                    fixture.identity);
            assertThat(output).containsAnyOf("缺少", "缺失", "没有", "未提供", "无法", "不足");
            var invocations = mockingDetails(fixture.tool).getInvocations();
            long toolCalls = invocations.stream().filter(call -> call.getMethod().getName().equals("executePython")
                    || call.getMethod().getName().equals("prepareAnalysisData")).count();
            verify(fixture.traces).insert(eq(fixture.identity.userId()), eq(fixture.identity.conversationId()),
                    eq(fixture.taskId.get()), eq("ANALYSIS"), eq("INPUT"), anyString());
            verify(fixture.traces).insert(eq(fixture.identity.userId()), eq(fixture.identity.conversationId()),
                    eq(fixture.taskId.get()), eq("ANALYSIS"), eq("FINAL"), anyString());
            System.out.println("ANALYSIS_MISSING_INPUTS_TOOL_CALLS=" + toolCalls);
            System.out.println("ANALYSIS_MISSING_INPUTS=" + output);
        }
    }

    /** 左连接无法证明右侧没有独有记录，使用反例检验实际结论。 */
    @Test
    void detectsThresholdWithoutInventory() throws Exception {
        try (var fixture = new Fixture()) {
            String output = fixture.service.analyze(
                    "本任务有两份完整记录：库存记录的商品编号为 101，安全库存记录的商品编号为 101 和 202。"
                            + "请使用 executePython 检查两边各自是否存在没有对应记录的商品，"
                            + "明确列出安全库存有记录但库存无记录的商品编号。只回答该核对任务。",
                    fixture.identity);
            assertThat(output).contains("202");
            verify(fixture.tool, atLeastOnce()).executePython(anyString(), anyList(), anyBoolean(),
                    nullable(String.class), any(ToolContext.class));
            fixture.verifyTrace();
            System.out.println("ANALYSIS_RIGHT_ONLY=" + output);
        }
    }
    /** 单一异常识别任务应交付异常对象和数值，供人工审阅是否扩展无关评判。 */
    @Test
    void identifiesOnlyRequestedInventoryException() throws Exception {
        try (var fixture = new Fixture()) {
            String output = fixture.service.analyze(
                    "数据集 ds_test 是完整库存，JSONL 每行依次是商品编号、库存数量，商品 A 和 B 均以件计量。"
                            + "请使用 prepareAnalysisData 和 executePython，找出库存严格小于 6 件的商品，"
                            + "返回商品编号及库存数量；本任务只需要异常识别结果。",
                    fixture.identity);
            assertThat(output).contains("B", "5");
            verify(fixture.tool, atLeastOnce()).prepareAnalysisData(argThat(ids -> ids.contains("ds_test")), any(ToolContext.class));
            verify(fixture.tool, atLeastOnce()).executePython(anyString(), argThat(ids -> ids.contains("ds_test")),
                    anyBoolean(), nullable(String.class), any(ToolContext.class));
            fixture.verifyTrace();
            System.out.println("ANALYSIS_TARGETED_EXCEPTION=" + output);
        }
    }

    /** 明确口径的商品比较需保留两条结果及差值。 */
    @Test
    void comparesProductsWithSpecifiedUnits() throws Exception {
        try (var fixture = new Fixture()) {
            String output = fixture.service.analyze(
                    "数据集 ds_test 是完整库存，JSONL 每行依次是商品编号、库存数量；商品 A 和 B 均以件计量，"
                            + "允许比较数量。请使用 prepareAnalysisData 和 executePython 读取数据，"
                            + "列出 A、B 各自库存及单位，比较两者并说明高多少件。",
                    fixture.identity);
            assertThat(output).contains("A", "B", "8", "5", "3");
            verify(fixture.tool, atLeastOnce()).prepareAnalysisData(argThat(ids -> ids.contains("ds_test")), any(ToolContext.class));
            verify(fixture.tool, atLeastOnce()).executePython(anyString(), argThat(ids -> ids.contains("ds_test")),
                    anyBoolean(), nullable(String.class), any(ToolContext.class));
            fixture.verifyTrace();
            System.out.println("ANALYSIS_PRODUCT_COMPARISON=" + output);
        }
    }

    /** 同一个智能体并发运行两个真实模型任务，校验身份、结果和流水隔离。 */
    @Test
    void isolatesConcurrentRealModelPythonTasks() throws Exception {
        try (var fixture = new Fixture()) {
            var firstIdentity = new AgentToolContext(91011, 92011);
            var secondIdentity = new AgentToolContext(91012, 92012);
            var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var first = executor.submit(() -> fixture.service.analyze(
                        "请务必调用 executePython 计算 13 乘以 17，报告精确结果。", firstIdentity));
                var second = executor.submit(() -> fixture.service.analyze(
                        "请务必调用 executePython 计算 19 乘以 23，报告精确结果。", secondIdentity));
                String firstAnswer = first.get(5, java.util.concurrent.TimeUnit.MINUTES);
                String secondAnswer = second.get(5, java.util.concurrent.TimeUnit.MINUTES);
                assertThat(firstAnswer).contains("221");
                assertThat(secondAnswer).contains("437");
                verify(fixture.tool, atLeast(2)).executePython(anyString(), anyList(), anyBoolean(),
                        nullable(String.class), any(ToolContext.class));
                var firstTask = org.mockito.ArgumentCaptor.forClass(String.class);
                var secondTask = org.mockito.ArgumentCaptor.forClass(String.class);
                verify(fixture.traces).insert(eq(91011L), eq(92011L), firstTask.capture(), eq("ANALYSIS"), eq("INPUT"), anyString());
                verify(fixture.traces).insert(eq(91012L), eq(92012L), secondTask.capture(), eq("ANALYSIS"), eq("INPUT"), anyString());
                assertThat(firstTask.getValue()).isNotEqualTo(secondTask.getValue());
                verify(fixture.traces).insert(eq(91011L), eq(92011L), eq(firstTask.getValue()), eq("ANALYSIS"), eq("FINAL"), contains("221"));
                verify(fixture.traces).insert(eq(91012L), eq(92012L), eq(secondTask.getValue()), eq("ANALYSIS"), eq("FINAL"), contains("437"));
                System.out.println("ANALYSIS_CONCURRENT_FIRST=" + firstAnswer);
                System.out.println("ANALYSIS_CONCURRENT_SECOND=" + secondAnswer);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /** 不依赖数据集时，验证模型实际调用 Python 并得到计算结果。 */
    @Test
    void executesCalculationWithoutDataset() throws Exception {
        try (var fixture = new Fixture()) {
            String output = fixture.service.analyze(
                    "请务必使用 executePython 计算 15、20、25 的平均值，并给出明确数值。",
                    fixture.identity);
            assertThat(output).contains("20");
            verify(fixture.tool, atLeastOnce()).executePython(anyString(), anyList(), anyBoolean(), nullable(String.class), any(ToolContext.class));
            
            fixture.verifyTrace();
            System.out.println("ANALYSIS_WITHOUT_DATASET=" + output);
        }
    }

    /** 使用合成数据集，验证真实模型准备文件并读取沙箱数据。 */
    @Test
    void preparesDatasetAndCalculatesTotal() throws Exception {
        try (var fixture = new Fixture()) {
            String output = fixture.service.analyze(
                    "已有完整数据集 ds_test，JSONL 每行是数组，按顺序为商品、库存数量，"
                            + "已确认两种商品均以件计量，业务口径允许按件合计，"
                            + "共两行，具体数值须通过工具读取。请务必用 prepareAnalysisData 和 "
                            + "executePython 读取数据集计算库存总数，回答精确数字。",
                    fixture.identity);
            assertThat(output).contains("13");
            verify(fixture.tool, atLeastOnce()).prepareAnalysisData(argThat(ids -> ids.contains("ds_test")), any(ToolContext.class));
            verify(fixture.tool, atLeastOnce()).executePython(contains("ds_test"),
                    argThat(ids -> ids.contains("ds_test")), anyBoolean(), nullable(String.class), any(ToolContext.class));
            
            fixture.verifyTrace();
            System.out.println("ANALYSIS_WITH_DATASET=" + output);
        }
    }

    /** 数据超过预览时，核对计算代码确实读取完整文件而非照抄预览。 */
    @Test
    void calculatesFromCompleteDatasetInsteadOfCopyingPreview() throws Exception {
        var content = new StringBuilder();
        for (int i = 1; i <= 57; i++) {
            content.append("[\"P").append(i).append("\",").append(i).append("]\n");
        }
        try (var fixture = new Fixture(content.toString(), 57)) {
            String output = fixture.service.analyze(
                    "数据集 ds_test 是完整库存，每行依次为商品编号和库存数量，均为件，"
                            + "允许求和。请计算全部商品库存数量之和并给出记录数。", fixture.identity);
            assertThat(output).contains("1653", "57");
            var code = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(fixture.tool, atLeastOnce()).executePython(code.capture(),
                    argThat(ids -> ids.contains("ds_test")), anyBoolean(), nullable(String.class), any(ToolContext.class));
            assertThat(code.getAllValues()).anyMatch(value -> value.contains("open(") || value.contains("read_json("));
            System.out.println("ANALYSIS_COMPLETE_DATASET=" + output);
        }
    }

    /** 真实模型在现有分析服务中收到格式错误反馈后继续原任务。 */
    @Test
    void repairsFinalHandoffInActualAnalysisAgent() throws Exception {
        var replaced = new java.util.concurrent.atomic.AtomicBoolean();
        var feedbackSeen = new java.util.concurrent.atomic.AtomicBoolean();
        var observer = new com.alibaba.cloud.ai.graph.agent.interceptor.ModelInterceptor() {
            @Override
            public com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse interceptModel(
                    com.alibaba.cloud.ai.graph.agent.interceptor.ModelRequest request,
                    com.alibaba.cloud.ai.graph.agent.interceptor.ModelCallHandler handler) {
                if (request.getMessages().toString().contains("最终交接结果的 JSON 格式错误")) {
                    feedbackSeen.set(true);
                }
                var response = handler.call(request);
                if (response.getMessage() instanceof AssistantMessage message && message.getToolCalls().isEmpty()
                        && replaced.compareAndSet(false, true)) {
                    System.out.println("ACTUAL_AGENT_ORIGINAL=" + message.getText());
                    return com.alibaba.cloud.ai.graph.agent.interceptor.ModelResponse.of(
                            new AssistantMessage(message.getText() + ","), response.getChatResponse());
                }
                return response;
            }

            @Override
            public String getName() {
                return "force_final_format_error";
            }
        };
        try (var fixture = new Fixture("[\"A\",8]\n[\"B\",5]\n", 2, observer)) {
            String output = fixture.service.analyze("请使用 executePython 一次计算 8+5 并说明结果。", fixture.identity);
            assertThat(output).contains("13");
            assertThat(replaced).isTrue();
            assertThat(feedbackSeen).isTrue();
            verify(fixture.tool, times(1)).executePython(anyString(), anyList(), anyBoolean(),
                    nullable(String.class), any(ToolContext.class));
            fixture.verifyTrace();
            System.out.println("ACTUAL_AGENT_REPAIRED=" + output);
        }
    }

    /** 读取开发库真实库存记录，在不写业务库的前提下验证分析计算。 */
    @Test
    @EnabledIfEnvironmentVariable(named = "ERP_REAL_DEV_ANALYSIS_IT", matches = "true")
    void analyzesDevelopmentStockRows() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", "erp-admin", "src", "main", "resources", "application-dev.yml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        var mapper = new ObjectMapper();
        var content = new StringBuilder();
        int count = 0;
        int zeroCount = 0;
        try (var connection = java.sql.DriverManager.getConnection(
                settings.getProperty("erp.ai.sql.datasource.url"),
                settings.getProperty("erp.ai.sql.datasource.username"),
                settings.getProperty("erp.ai.sql.datasource.password"))) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement(
                    "SELECT product_code, stock_qty FROM warehouse_stock ORDER BY id");
                 var rows = statement.executeQuery()) {
                while (rows.next()) {
                    content.append(mapper.writeValueAsString(List.of(rows.getString(1), rows.getLong(2)))).append('\n');
                    count++;
                    if (rows.getLong(2) == 0) {
                        zeroCount++;
                    }
                }
            }
        }
        assertThat(count).isPositive();
        System.out.printf("DEV_STOCK_EXPECTED rows=%d zero=%d nonzero=%d%n", count, zeroCount, count - zeroCount);
        try (var fixture = new Fixture(content.toString(), count)) {
            String output = fixture.service.analyze("数据集 ds_test 是开发库实际库存记录，每行依次为商品编码、库存数量。"
                    + "请使用 Python 读取完整文件，统计库存数量等于零的记录数、非零记录数和总记录数。"
                    + "只统计记录条数，不要合计不同商品的库存数量；简要说明依据和限制。", fixture.identity);
            assertThat(output).contains(Integer.toString(count), Integer.toString(zeroCount), Integer.toString(count - zeroCount));
            verify(fixture.tool, atLeastOnce()).prepareAnalysisData(argThat(ids -> ids.contains("ds_test")), any(ToolContext.class));
            System.out.println("DEV_STOCK_ANALYSIS=" + output);
        }
    }

    /** 单位未知时不得把数量字段当作单位一致的证据。 */
    @Test
    void explainsMissingUnitsInsteadOfAssumingTheyMatch() throws Exception {
        try (var fixture = new Fixture()) {
            String output = fixture.service.analyze(
                    "已有完整数据集 ds_test，JSONL 每行依次是商品、库存数量，共两行；"
                            + "数据没有提供单位或可合并的业务口径。请读取数据并分析库存总量，"
                            + "说明现有数据能支持什么结论。",
                    fixture.identity);
            // 允许“若单位一致”等条件句；业务含义需结合完整输出人工核对。
            assertThat(output).contains("单位").containsAnyOf("无法", "不能", "未提供", "没有");
            verify(fixture.tool, atLeastOnce()).prepareAnalysisData(argThat(ids -> ids.contains("ds_test")), any(ToolContext.class));
            
            fixture.verifyTrace();
            System.out.println("ANALYSIS_UNKNOWN_UNITS=" + output);
        }
    }

    /** 结果文件需实际保存并将服务端生成的 ID 交还模型。 */
    @Test
    void returnsGeneratedFileId() throws Exception {
        try (var fixture = new Fixture()) {
            String output = fixture.service.analyze(
                    "请务必使用 executePython 生成一个 CSV 文件，含表头和值 42，"
                            + "将文件放入约定的输出目录，最终答复中原样提供工具返回的结果 ID。",
                    fixture.identity);
            assertThat(fixture.savedId.get()).isNotBlank();
            assertThat(output).contains(fixture.savedId.get());
            assertThat(fixture.savedTaskId.get()).isEqualTo(fixture.taskId.get());
            
            fixture.verifyTrace();
            System.out.println("ANALYSIS_FILE_RESULT=" + output);
        }
    }

    /** 构建真实沙箱和模型；数据集权限与结果存储使用测试替身。 */
    private static final class Fixture implements AutoCloseable {
        final AgentToolContext identity = new AgentToolContext(91001, 92001);
        final PythonSandboxRuntime runtime;
        PythonAnalysisTool tool;
        final AnalysisAgentService service;
        final AiAgentTraceMapper traces = mock(AiAgentTraceMapper.class);
        final java.util.concurrent.atomic.AtomicReference<String> taskId = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> savedId = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> savedTaskId = new java.util.concurrent.atomic.AtomicReference<>();
        final Logger sdkLogger;
        final Level previousLevel;

        Fixture() throws Exception {
            this("[\"A\",8]\n[\"B\",5]\n", 2);
        }

        Fixture(String datasetContent, int rowCount,
                com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor... extraInterceptors) throws Exception {
            sdkLogger = (Logger) LoggerFactory.getLogger("io.agentscope.runtime.sandbox.manager.SandboxService");
            previousLevel = sdkLogger.getLevel();
            sdkLogger.setLevel(Level.WARN);
            doAnswer(call -> {
                if ("INPUT".equals(call.getArgument(4))) {
                    taskId.set(call.getArgument(2));
                } else if ("FINAL".equals(call.getArgument(4))) {
                    System.out.println("ANALYSIS_MODEL_FINAL=" + call.getArgument(5));
                }
                return null;
            }).when(traces).insert(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString());
            var mapper = new ObjectMapper();
            var datasets = mock(DatasetManager.class);
            var record = new DatasetRecord("ds_test", "run", identity.userId(), identity.conversationId(), "库存",
                    List.of(new SqlQueryColumn("product", "商品", Types.VARCHAR, "VARCHAR", false),
                            new SqlQueryColumn("qty", "库存数量", Types.INTEGER, "INT", false)),
                    rowCount, true, "READY", null, null);
            when(datasets.get("ds_test", identity.userId(), identity.conversationId())).thenReturn(record);
            when(datasets.openReady("ds_test", identity.userId(), identity.conversationId())).thenAnswer(call ->
                    new DatasetInput(record, new ByteArrayInputStream(datasetContent
                            .getBytes(StandardCharsets.UTF_8))));
            var store = mock(AnalysisResultStore.class);
            when(store.save(any(), anyString(), anyString(), anyString(), anyString(), nullable(String.class),
                    nullable(String.class), anyList(), any())).thenAnswer(call -> {
                        var output = new ByteArrayOutputStream();
                        call.<AnalysisResultStore.FileWriter>getArgument(8).write(output);
                        var result = new AiAnalysisResult();
                        result.setResultId(UUID.randomUUID().toString());
                        savedTaskId.set(call.getArgument(1));
                        savedId.set(result.getResultId());
                        return result;
                    });
            runtime = new PythonSandboxRuntime(mapper, "erp-ai-sandbox-probe:local");
            tool = spy(new PythonAnalysisTool(new PythonDatasetPreparer(datasets), store));
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                System.out.println("ANALYSIS_TOOL_PREPARE input=" + invocation.getArgument(0) + " result=" + result);
                return result;
            }).when(tool).prepareAnalysisData(anyList(), any(ToolContext.class));
            doAnswer(invocation -> {
                Object result = invocation.callRealMethod();
                System.out.println("ANALYSIS_TOOL_EXECUTE code=" + invocation.getArgument(0)
                        + " datasets=" + invocation.getArgument(1) + " save=" + invocation.getArgument(2)
                        + " result=" + result);
                return result;
            }).when(tool).executePython(anyString(), anyList(), anyBoolean(), nullable(String.class), any(ToolContext.class));
            var yaml = new YamlPropertiesFactoryBean();
            yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
            var settings = Objects.requireNonNull(yaml.getObject());
            String thinking = settings.getProperty("erp.ai.model.enable-thinking");
            Boolean enabled = thinking == null || thinking.isBlank() ? null : Boolean.valueOf(thinking.trim());
            var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                    settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.pro"),
                    0.2, Duration.ofSeconds(90), enabled);
            String prompt = new ClassPathResource("prompts/analysis-agent.md")
                    .getContentAsString(StandardCharsets.UTF_8);
            var graph = mock(com.qiheng.erp.ai.knowledge.graph.BusinessGraphProvider.class);
            when(graph.render()).thenReturn("【ERP 业务实体关系参考】仓库关联库存；具体数据与权限以工具结果为准。");
            var interceptors = new java.util.ArrayList<com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor>();
            interceptors.add(new com.qiheng.erp.ai.agent.interceptor.BusinessGraphInterceptor(graph));
            interceptors.addAll(List.of(extraInterceptors));
            service = new AnalysisAgentService(model, tool, runtime, prompt, traces, mapper, null, null,
                    new ToolJsonInterceptor(mapper), null, mock(AnalysisResultTool.class), null, null, null, null, null,
                    interceptors.toArray(com.alibaba.cloud.ai.graph.agent.interceptor.Interceptor[]::new));
        }

        /** 核对真实模型调用后，各关键事件都关联同一个分析任务。 */
        void verifyTrace() {
            verify(traces).insert(eq(identity.userId()), eq(identity.conversationId()), eq(taskId.get()),
                    eq("ANALYSIS"), eq("INPUT"), anyString());
            verify(traces, atLeastOnce()).insert(eq(identity.userId()), eq(identity.conversationId()), eq(taskId.get()),
                    eq("ANALYSIS"), eq("TOOL_CALL"), anyString());
            verify(traces, atLeastOnce()).insert(eq(identity.userId()), eq(identity.conversationId()), eq(taskId.get()),
                    eq("ANALYSIS"), eq("TOOL_RESULT"), anyString());
            verify(traces).insert(eq(identity.userId()), eq(identity.conversationId()), eq(taskId.get()),
                    eq("ANALYSIS"), eq("FINAL"), anyString());
        }

        /** 测试结束时清理任务容器并恢复日志级别。 */
        @Override
        public void close() {
            runtime.close();
            sdkLogger.setLevel(previousLevel);
        }
    }
}
