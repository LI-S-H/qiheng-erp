package com.qiheng.erp.ai.sql.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.OpenAiCompatibleChatModel;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.sql.graph.model.SqlModelClient;
import com.qiheng.erp.ai.sql.graph.model.output.SqlQueryOutput;
import com.qiheng.erp.ai.sql.graph.model.output.SqlNeedInfoOutput;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.qiheng.erp.ai.sql.schema.AuthorizedSchemaProvider;
import com.qiheng.erp.ai.sql.execution.ReadOnlySqlExecutor;
import com.qiheng.erp.ai.sql.execution.SqlGuardGapReporter;
import com.qiheng.erp.ai.sql.execution.model.ExecutionOutcome;
import com.qiheng.erp.ai.sql.execution.model.SqlExecutionRequest;
import com.qiheng.erp.ai.sql.guard.DruidSqlGuard;
import com.qiheng.erp.ai.sql.guard.model.GuardResult;
import com.qiheng.erp.ai.sql.guard.model.SqlGuardLimits;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 真实账号授权与真实模型的只读探测；仅打印查询诊断和聚合值，不打印凭据或业务明细。 */
@SpringJUnitConfig(SchemaProviderMysqlIntegrationTest.TestConfig.class)
@EnabledIfEnvironmentVariable(named = "ERP_REAL_ACCOUNT_MODEL_TEST", matches = "true")
class RealAccountModelReadOnlyIT {
    @Autowired private AuthorizedSchemaProvider schemas;
    @Autowired private JdbcTemplate jdbc;

    /** 仅读取权限元数据和执行只读聚合，不写业务库或数据集。 */
    @Test
    void realAccountsGenerateAndExecuteAuthorizedCount() throws Exception {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new FileSystemResource(Path.of("..", ".env.ai.yaml")));
        var settings = Objects.requireNonNull(yaml.getObject());
        var model = OpenAiCompatibleChatModel.create(settings.getProperty("erp.ai.api-key"),
                settings.getProperty("erp.ai.base-url"), settings.getProperty("erp.ai.model.flash"),
                0.2, Duration.ofSeconds(90), false);
        var options = ((OpenAiChatOptions) model.getDefaultOptions()).copy();
        options.setResponseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        var client = new SqlModelClient(model.mutate().defaultOptions(options).build(), new ObjectMapper());
        String prompt = SqlModelClient.loadPrompt("prompts/sql/generate.md");
        String url = System.getProperty("ai.sql.url", "jdbc:mysql://localhost:13307/erp?useSSL=false&allowPublicKeyRetrieval=true&allowMultiQueries=false");
        String user = System.getProperty("ai.sql.username", settings.getProperty("erp.ai.sql.datasource.username", "erp_ai_ro"));
        String password = System.getProperty("ai.sql.password", settings.getProperty("erp.ai.sql.datasource.password", ""));
        var datasource = new AiSqlProperties.Datasource(url, user, password, "com.mysql.cj.jdbc.Driver",
                "real-erp-account-readonly", 1, 1, 2, 3000, "SELECT 1", 1000);
        var executor = new ReadOnlySqlExecutor(new AiSqlProperties(datasource, true, 200, 5000, 10, 10), mock(SqlGuardGapReporter.class));
        var guard = new DruidSqlGuard();
        try {
            int exercised = 0;
            List<String> incomplete = new ArrayList<>();
            for (String account : List.of("admin", "manager01", "purchase01", "sales01", "warehouse01")) {
                List<String> permissions = permissions(account);
                var schema = schemas.provideFreshSnapshot(permissions);
                if (schema.tables().isEmpty()) {
                    System.out.printf("ERP_READONLY account=%s tables=0 schema_chars=%d result=NO_VISIBLE_TABLES%n", account, schema.schemaText().length());
                    continue;
                }
                exercised++;
                var table = schema.tables().getFirst();
                Map<String, Object> input = new LinkedHashMap<>();
                String goal = "统计当前可查询的 " + table.businessName() + "（" + table.name() + "）记录数";
                input.put("requirement", Map.of("goal", goal, "dataRequirement", "FULL"));
                System.out.printf("ERP_READONLY account=%s target=%s goal=%s tables=%d schema_chars=%d%n",
                        account, table.name(), goal, schema.tables().size(), schema.schemaText().length());
                input.put("supplements", List.of());
                input.put("schema", schema.schemaText());
                input.put("knowledge", "遵循授权结构所示业务定义和软删除规则。");
                input.put("pageSize", 200);
                input.put("maxTotalRows", 5000);
                input.put("currentDate", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
                input.put("timeZone", "Asia/Shanghai");
                input.put("previousSql", "");
                input.put("feedback", "");
                boolean success = false;
                int attempts = 0;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    attempts = attempt;
                    var generated = client.generate(prompt, input);
                    if (!(generated instanceof SqlQueryOutput query)) {
                        String reason = generated instanceof SqlNeedInfoOutput info ? info.message() : "未知模型分支";
                        System.out.printf("ERP_READONLY account=%s attempt=%d decision=NEED_INFO message=%s%n", account, attempt, reason);
                        break;
                    }
                    System.out.printf("ERP_READONLY account=%s attempt=%d needFullData=%s sql=%s%n",
                            account, attempt, query.needFullData(), query.sql());
                    if (!isCountOnly(query.sql())) {
                        input.put("previousSql", query.sql());
                        input.put("feedback", "本次需求是统计记录数，只返回一个 COUNT 聚合列，不分组。");
                        System.out.printf("ERP_READONLY account=%s attempt=%d decision=RETRY reason=NOT_COUNT_ONLY%n", account, attempt);
                        continue;
                    }
                    var checked = guard.check(query.sql(), schema, new SqlGuardLimits(5000), query.needFullData());
                    if (checked instanceof GuardResult.Rejected rejected) {
                        System.out.printf("ERP_READONLY account=%s attempt=%d guard=%s feedback=%s%n",
                                account, attempt, rejected.code(), rejected.message());
                        input.put("previousSql", query.sql());
                        input.put("feedback", rejected.message());
                        continue;
                    }
                    if (!((GuardResult.Ok) checked).guardedSql().tables().contains(table.name())) {
                        input.put("previousSql", query.sql());
                        input.put("feedback", "请统计指定目标表，不要替换查询对象。");
                        System.out.printf("ERP_READONLY account=%s attempt=%d decision=RETRY reason=WRONG_TARGET%n", account, attempt);
                        continue;
                    }
                    if (((GuardResult.Ok) checked).guardedSql().limit() != null) {
                        input.put("previousSql", query.sql());
                        input.put("feedback", "本次要求完整结果，请去掉 LIMIT。");
                        System.out.printf("ERP_READONLY account=%s attempt=%d decision=RETRY reason=FULL_WITH_LIMIT%n", account, attempt);
                        continue;
                    }
                    System.out.printf("ERP_READONLY account=%s attempt=%d guard=PASS%n", account, attempt);
                    var outcome = executor.execute(new SqlExecutionRequest("readonly-probe", "attempt-" + attempt,
                            ((GuardResult.Ok) checked).guardedSql(), query.needFullData()));
                    if (outcome instanceof ExecutionOutcome.Success result) {
                        assertThat(result.result().rows()).hasSize(1);
                        var row = result.result().rows().getFirst();
                        assertThat(row).hasSize(1);
                        assertThat(row.getFirst()).isInstanceOf(Number.class);
                        System.out.printf("ERP_READONLY account=%s attempt=%d execution=SUCCESS rows=%d count=%s%n",
                                account, attempt, result.result().rowCount(), row.getFirst());
                        success = true;
                    } else if (outcome instanceof ExecutionOutcome.Failed failed) {
                        System.out.printf("ERP_READONLY account=%s attempt=%d execution=%s feedback=%s%n",
                                account, attempt, failed.kind(), failed.modelMessage());
                        input.put("previousSql", query.sql());
                        input.put("feedback", failed.modelMessage());
                        continue;
                    } else System.out.printf("ERP_READONLY account=%s attempt=%d execution=NEEDS_DECLARATION%n", account, attempt);
                    break;
                }
                System.out.printf("ERP_READONLY account=%s total_attempts=%d result=%s%n", account, attempts, success ? "SUCCESS" : "NOT_COMPLETED");
                if (!success) incomplete.add(account);
            }
            assertThat(exercised).isPositive();
            assertThat(incomplete).as("未完成授权聚合查询的账号").isEmpty();
        } finally {
            executor.destroy();
        }
    }

    /** 仅允许单列 COUNT 且无 GROUP BY，确保输出不会包含业务明细。 */
    private boolean isCountOnly(String sql) {
        try {
            var statement = SQLUtils.parseSingleMysqlStatement(sql);
            if (!(statement instanceof SQLSelectStatement select)) return false;
            var block = select.getSelect().getQueryBlock();
            if (block == null || block.getGroupBy() != null || block.getSelectList().size() != 1) return false;
            return block.getSelectList().getFirst().getExpr() instanceof SQLAggregateExpr aggregate
                    && "COUNT".equalsIgnoreCase(aggregate.getMethodName());
        } catch (RuntimeException error) {
            return false;
        }
    }

    /** 从角色读取权限；SQL 均为 SELECT，未登录或禁用账号不参加测试。 */
    private List<String> permissions(String account) throws Exception {
        var rows = jdbc.queryForList("""
                SELECT u.is_admin, r.permission_codes FROM sys_user u
                LEFT JOIN sys_user_role ur ON ur.user_id=u.id
                LEFT JOIN sys_role r ON r.id=ur.role_id AND r.status=1 AND r.deleted=0
                WHERE u.username=? AND u.status=1 AND u.deleted=0
                """, account);
        List<String> permissions = new ArrayList<>();
        if (!rows.isEmpty() && ((Number) rows.getFirst().get("is_admin")).intValue() == 1) return List.of("*");
        var mapper = new ObjectMapper();
        for (var row : rows) {
            if (row.get("permission_codes") != null) {
                permissions.addAll(mapper.readValue(row.get("permission_codes").toString(), new TypeReference<List<String>>() {}));
            }
        }
        return permissions;
    }
}
