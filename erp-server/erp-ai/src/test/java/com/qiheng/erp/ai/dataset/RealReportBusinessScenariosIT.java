package com.qiheng.erp.ai.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallHandler;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallRequest;
import com.alibaba.cloud.ai.graph.agent.interceptor.ToolCallResponse;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.dataset.tool.ReadDatasetTool;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.python.tool.AnalysisResultTool;
import com.qiheng.erp.ai.python.result.AnalysisResultStore;
import com.qiheng.erp.ai.report.ReportAgents;
import com.qiheng.erp.ai.report.ReportAnalysisStep;
import com.qiheng.erp.ai.report.ReportReadTools;
import com.qiheng.erp.ai.report.ReportTool;
import com.qiheng.erp.ai.report.graph.ReportConversationReader;
import com.qiheng.erp.ai.report.graph.ReportService;
import com.qiheng.erp.ai.report.graph.ReportMaterialPreparer;
import com.qiheng.erp.ai.agent.TrustedToolContextInterceptor;
import com.qiheng.erp.ai.sql.execution.model.QueryResult;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Types;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 使用开发库真实库存、隔离库和真实模型验证报告工具；不写开发库。 */
@EnabledIfEnvironmentVariable(named = "ERP_REAL_REPORT_BUSINESS_IT", matches = "true")
class RealReportBusinessScenariosIT {
    @TempDir Path directory;

    /** 隔离测试库连接串必须由环境变量提供，避免把测试库凭据写进仓库。 */
    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        Assumptions.assumeTrue(value != null && !value.isBlank(), "缺少环境变量 " + name);
        return value;
    }

    @Test
    void runsTwentyBusinessRequests() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", "erp-admin", "src", "main", "resources", "application-dev.yml")));
        var db = Objects.requireNonNull(yaml.getObject());
        List<List<Object>> rows = new ArrayList<>();
        try (var connection = DriverManager.getConnection(db.getProperty("erp.ai.sql.datasource.url"),
                db.getProperty("erp.ai.sql.datasource.username"), db.getProperty("erp.ai.sql.datasource.password"))) {
            connection.setReadOnly(true);
            try (var statement = connection.prepareStatement("SELECT product_code, stock_qty FROM warehouse_stock ORDER BY id");
                 var result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(List.of(result.getString(1), result.getBigDecimal(2)));
                }
            }
        }
        assertThat(rows).isNotEmpty();
        long zero = rows.stream().filter(row -> ((java.math.BigDecimal) row.get(1)).signum() == 0).count();
        String findings = "开发库库存记录共 " + rows.size() + " 条，其中零库存 " + zero + " 条；"
                + "数据字段仅包含商品编码和库存数量，没有单位、仓库、安全库存、销售或时间字段。";

        var isolated = new DriverManagerDataSource(
                requiredEnv("DATASET_IT_URL"),
                System.getenv("DATASET_IT_USER"), System.getenv("DATASET_IT_PASSWORD"));
        var jdbc = new JdbcTemplate(isolated);
        jdbc.execute(Files.readString(Path.of("..", "..", "docs", "database", "sql", "029_ai_dataset.sql")));
        var mapper = new ObjectMapper();
        var datasets = new DatasetManager(DatasetMapperTestFactory.create(isolated, mapper), ignored -> {},
                mapper, directory.resolve("datasets").toString(), 0);
        var columns = List.of(new SqlQueryColumn("product_code", "product_code", Types.VARCHAR, "VARCHAR", false),
                new SqlQueryColumn("stock_qty", "stock_qty", Types.DECIMAL, "DECIMAL", false));
        String run = "report_real_" + UUID.randomUUID();
        long user = 910101L;
        long conversation = 920101L;
        var staged = datasets.stage(new DatasetRequest(run, user, conversation, "开发库库存记录",
                        "SELECT product_code, stock_qty FROM warehouse_stock ORDER BY id", 1),
                new QueryResult(columns, rows, rows.size(), true, 1, 1, "real-report"));
        var published = datasets.approve(staged.datasetId(), user, conversation,
                new SqlDataDescription("开发库库存记录，未提供单位", "一行一条库存记录",
                        List.of(new SqlDataField(0, "product_code", "商品编码"),
                                new SqlDataField(1, "stock_qty", "库存数量")), "未提供单位及时间口径"));
        var aiYaml = new YamlPropertiesFactoryBean();
        aiYaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var ai = Objects.requireNonNull(aiYaml.getObject());
        var model = OpenAiCompatibleChatModel.create(ai.getProperty("erp.ai.api-key"),
                ai.getProperty("erp.ai.base-url"), ai.getProperty("erp.ai.model.pro"), 0.2, Duration.ofSeconds(90), null);
        var reader = spy(new ReadDatasetTool(datasets));
        var agents = new ReportAgents(model, new ReportReadTools(reader, mock(AnalysisResultTool.class)), mapper,
                Files.readString(Path.of("src/main/resources/prompts/report-writer.md")),
                Files.readString(Path.of("src/main/resources/prompts/report-reviewer.md")), null, null);
        var messages = mock(AiMessageMapper.class);
        var conversations = mock(AiConversationMapper.class);
        when(conversations.selectOwned(conversation, user)).thenReturn(new AiConversation());
        var service = new ReportService(agents, conversations,
                new ReportMaterialPreparer(datasets, mock(AnalysisResultStore.class)),
                new ReportConversationReader(messages, mapper));
        var tool = new ReportTool(service);
        var context = new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, new AgentToolContext(user, conversation),
                TrustedToolContextInterceptor.CURRENT_RUN_ID, run));
        List<String> scenarios = List.of(
                "统计库存记录数和零库存记录数，解释统计口径。",
                "分析零库存商品，列出商品编码和数量。",
                "分析非零库存商品，说明样本范围。",
                "对库存记录做完整性核对，列出字段和记录数。",
                "列出库存数量最高的三条商品记录，说明不能跨商品合计。",
                "列出库存数量最低的三条商品记录，说明单位是否已知。",
                "列出库存等于零的商品，说明是否能判断缺货原因。",
                "统计库存大于零和等于零的记录数，说明比例的分母。",
                "分析库存数量为负的记录是否存在。",
                "检查商品编码是否有重复记录，说明每行代表的范围。",
                "查看库存为 1 的商品及其记录数。",
                "查看库存数量不超过 5 的商品记录。",
                "查看库存数量超过 10 的商品记录。",
                "分析库存分布，按零、正数、负数分别统计记录数。",
                "列出商品库存明细并解释数据没有提供的单位信息。",
                "评估是否能够计算各仓库库存，说明依据及缺失字段。",
                "评估是否能够计算安全库存不足数量，说明缺少什么数据。",
                "评估是否能够计算库存金额，说明缺少什么数据。",
                "评估是否能够比较本月与上月库存，说明缺少什么数据。",
                "评估是否能够计算各商品销量和库存周转率，说明数据限制。"
        );
        Path output = Path.of("..", "target", "real-report-business-" + UUID.randomUUID() + ".jsonl")
                .toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        int index = 0;
        int completed = 0;
        int attempted = 0;
        try {
            for (String request : scenarios) {
                index++;
                String selected = System.getProperty("erp.report.scenario", "");
                if (!selected.isBlank() && !selected.equals(Integer.toString(index))) {
                    continue;
                }
                attempted++;
                String scenarioRun = run + "_" + index;
                when(messages.selectRunMessages(user, conversation, scenarioRun)).thenReturn(List.of(
                        new AiMessage().setKind("USER_INPUT").setContent(mapper.writeValueAsString(request))));
                var scenarioContext = new ToolContext(Map.of(AgentToolContext.CONTEXT_KEY, new AgentToolContext(user, conversation),
                        TrustedToolContextInterceptor.CURRENT_RUN_ID, scenarioRun));
                var step = new ReportAnalysisStep(1, "依据开发库库存记录回答用户需求", "读取库存数据集",
                        findings, List.of(published.datasetId()), List.of());
                long start = System.currentTimeMillis();
                Map<String, Object> record;
                try {
                    Map<String, Object> outcome = tool.createReport(List.of(step), "未提供单位、时间及销售数据", scenarioContext);
                    if ("COMPLETED".equals(outcome.get("status"))) {
                        completed++;
                    }
                    record = Map.of("index", index, "request", request, "result", outcome,
                            "elapsedMs", System.currentTimeMillis() - start);
                } catch (Exception error) {
                    record = Map.of("index", index, "request", request, "errorType", error.getClass().getSimpleName(),
                            "message", String.valueOf(error.getMessage()), "elapsedMs", System.currentTimeMillis() - start);
                }
                Files.writeString(output, mapper.writeValueAsString(record) + "\n", StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                System.out.println("REPORT_SCENARIO " + index + "/20 status="
                        + (record.containsKey("result") ? ((Map<?, ?>) record.get("result")).get("status") : record.get("errorType")));
            }
            System.out.println("REPORT_BUSINESS_DATA rows=" + rows.size() + " zero=" + zero
                    + " completed=" + completed + "/" + attempted + " output=" + output);
            if (Boolean.getBoolean("erp.report.mainEntry")) {
                int mainIndex = 0;
                int mainCompleted = 0;
                for (String mainGoal : List.of(
                        "汇报库存记录数、零库存记录数和资料局限",
                        "汇报安全库存不足数量是否能够计算，以及缺少哪些条件",
                        "汇报库存数据能否用于计算金额和月度变化")) {
                    mainIndex++;
                    String mainRun = run + "_main_" + mainIndex;
                    String mainRequest = "请使用报告工具，根据以下已完成的分析生成报告，目标：" + mainGoal
                            + "。已查询开发库库存，" + findings + "依据数据集 ID 为 " + published.datasetId() + "。";
                    when(messages.selectRunMessages(user, conversation, mainRun)).thenReturn(List.of(
                            new AiMessage().setKind("USER_INPUT").setContent(mapper.writeValueAsString(mainRequest))));
                    AtomicInteger reportCalls = new AtomicInteger();
                    List<String> toolResults = new java.util.concurrent.CopyOnWriteArrayList<>();
                    ToolInterceptor counter = new ToolInterceptor() {
                        @Override
                        public ToolCallResponse interceptToolCall(ToolCallRequest request, ToolCallHandler handler) {
                            if ("createReport".equals(request.getToolName())) {
                                reportCalls.incrementAndGet();
                                ToolCallResponse response = handler.call(request);
                                toolResults.add(String.valueOf(response.getResult()));
                                return response;
                            }
                            return handler.call(request);
                        }

                        @Override
                        public String getName() {
                            return "report_test_counter";
                        }
                    };
                    var main = ReactAgent.builder().name("real-report-main").model(model)
                            .systemPrompt(Files.readString(Path.of("src/main/resources/prompts/main-agent.md")))
                            .methodTools(tool).interceptors(new TrustedToolContextInterceptor(), counter).build();
                    var mainConfig = RunnableConfig.builder().threadId("report-main-" + UUID.randomUUID())
                            .addMetadata(TrustedToolContextInterceptor.TRUSTED_CONTEXT, new AgentToolContext(user, conversation))
                            .addMetadata("runId", mainRun).build();
                    String answer = "";
                    String error = "";
                    try {
                        String response = main.call(mainRequest, mainConfig).getText();
                        if (response != null) {
                            answer = response;
                        }
                    } catch (Exception failure) {
                        error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
                    }
                    if (error.isBlank() && reportCalls.get() > 0 && !answer.isBlank()
                            && toolResults.stream().anyMatch(value -> value.contains("\"COMPLETED\""))) {
                        mainCompleted++;
                    }
                    Files.writeString(output, mapper.writeValueAsString(Map.of("scenario", "main-agent",
                            "index", mainIndex, "request", mainRequest, "toolCalls", reportCalls.get(),
                            "toolResults", toolResults, "answer", answer, "error", error)) + "\n",
                            StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.APPEND);
                    System.out.println("REPORT_MAIN_ENTRY " + mainIndex + "/3 toolCalls=" + reportCalls.get()
                            + " answerChars=" + answer.length() + " error=" + !error.isBlank());
                }
                assertThat(mainCompleted).as("主智能体报告工具应完成，具体结果见 " + output).isEqualTo(3);
            }
            verify(reader, atLeastOnce()).readDataset(anyString(), any(), any(), any());
            assertThat(completed).as("所有实际运行的报告场景应成功返回报告，具体输出见 " + output)
                    .isEqualTo(attempted);
        } finally {
            jdbc.update("DELETE FROM ai_dataset WHERE sql_run_id = ?", run);
        }
    }
}
