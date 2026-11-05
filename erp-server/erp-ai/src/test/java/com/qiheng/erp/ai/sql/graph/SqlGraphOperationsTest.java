package com.qiheng.erp.ai.sql.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.dataset.SqlDataDescription;
import com.qiheng.erp.ai.dataset.SqlDataField;
import com.qiheng.erp.ai.sql.schema.SchemaProvider;
import com.qiheng.erp.ai.sql.execution.ReadOnlySqlExecutor;
import com.qiheng.erp.ai.sql.execution.model.ExecutionOutcome;
import com.qiheng.erp.ai.sql.execution.model.QueryResult;
import com.qiheng.erp.ai.sql.execution.model.SqlErrorKind;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import com.qiheng.erp.ai.sql.guard.DruidSqlGuard;
import com.qiheng.erp.ai.sql.guard.SqlGuard;
import com.qiheng.erp.ai.sql.guard.model.GuardResult;
import com.qiheng.erp.ai.sql.guard.model.GuardedSql;
import com.qiheng.erp.ai.knowledge.KnowledgeProvider;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用真实 SQL 守卫检验节点边界；模型、数据库和数据集分别受控，不访问运行库。 */
class SqlGraphOperationsTest {
    private static final String DESCRIPTION_JSON = "\"dataDescription\":{\"content\":\"库存明细\",\"grain\":\"每行一条库存\",\"fields\":[{\"index\":0,\"name\":\"id\",\"meaning\":\"编号\"},{\"index\":1,\"name\":\"qty\",\"meaning\":\"数量\"}],\"limitations\":\"\"}";
    private static final SqlDataDescription DESCRIPTION = new SqlDataDescription("库存明细", "每行一条库存", List.of(new SqlDataField(0, "id", "编号"), new SqlDataField(1, "qty", "数量")), "");
    private static final String SQL = "SELECT id, qty FROM stock";
    private static final List<SqlQueryColumn> COLUMNS = List.of(
            new SqlQueryColumn("id", "id", Types.BIGINT, "BIGINT", false),
            new SqlQueryColumn("qty", "qty", Types.BIGINT, "BIGINT", false));

    private final ObjectMapper mapper = new ObjectMapper();
    private final SchemaProvider schemas = mock(SchemaProvider.class);
    private final KnowledgeProvider knowledge = mock(KnowledgeProvider.class);
    private final ChatModel model = mock(ChatModel.class);
    private final ReadOnlySqlExecutor executor = mock(ReadOnlySqlExecutor.class);
    private final LoginUserService users = mock(LoginUserService.class);
    private final DatasetManager datasets = mock(DatasetManager.class);
    private TestSqlOperations operations;

    /** 模拟真实账户权限与授权快照，节点必须每次重新读取。 */
    @BeforeEach
    void configureDependencies() {
        LoginUser user = new LoginUser();
        user.setUserId(7L);
        user.setPermissionCodes(List.of("ai:assistant:use", "warehouse:query"));
        when(users.findByUserId(7L)).thenReturn(user);
        when(schemas.provideFreshSnapshot(anyList())).thenReturn(snapshot(true));
        when(knowledge.provide(anyList())).thenReturn("库存数量保存真实值乘以100。");
        when(datasets.readStaged("ds_stage", 7L, 9L, 5)).thenReturn(List.of());
        when(datasets.get("ds_stage", 7L, 9L)).thenReturn(record("ds_stage", 0, "STAGED"));
        operations = operations(new DruidSqlGuard());
    }

    /** 生成提示词必须含真实业务需求、授权结构、口径、日期和时区。 */
    @Test
    void generationReceivesBusinessContextWithoutDelegatingIdentityToModel() {
        when(model.call(any(Prompt.class))).thenReturn(reply(
                "{\"decision\":\"QUERY\",\"sql\":\"SELECT id, qty FROM stock\",\"needFullData\":false}"));

        var output = operations.generate(operations.prepare(input("LIMITED")));

        var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        String content = prompt.getValue().getContents();
        assertThat(content).contains("库存明细", "stock", "乘以100", "currentDate", "Asia/Shanghai");
        assertThat(content).doesNotContain("sqlRunId", "conversationId", "researchId");
        assertThat(output).containsEntry("sql", SQL).containsEntry("attempt", 1).containsEntry("route", "next");
        verify(schemas, times(2)).provideFreshSnapshot(anyList());
    }

    /** 跨午夜恢复和生成修复沿用最初业务日期，新查询由准备节点重新冻结日期。 */
    @Test
    void retriesAndRestoredCheckpointsKeepTheirOriginalBusinessDate() throws Exception {
        var prepared = operations.prepare(input("LIMITED"));
        assertThat(prepared).containsEntry("currentDate", LocalDate.now(ZoneId.of("Asia/Shanghai")).toString())
                .containsEntry("timeZone", "Asia/Shanghai");

        // 模拟前一天留下的持久检查点，恢复发生时系统日期已经不同。
        prepared.put("currentDate", "2026-10-26");
        var restored = mapper.readValue(mapper.writeValueAsString(prepared),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        when(model.call(any(Prompt.class))).thenReturn(reply("不是JSON"), reply(
                "{\"decision\":\"QUERY\",\"sql\":\"SELECT id, qty FROM stock\",\"needFullData\":false}"));

        var completed = operations.generate(operations.generate(restored));

        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues()).allSatisfy(prompt -> assertThat(prompt.getContents())
                .contains("\"currentDate\":\"2026-10-26\"", "\"timeZone\":\"Asia/Shanghai\""));
        assertThat(completed).containsEntry("currentDate", "2026-10-26");
        assertThat(operations.prepare(input("LIMITED"))).containsEntry("currentDate",
                LocalDate.now(ZoneId.of("Asia/Shanghai")).toString());
    }

    /** 缺少必要业务输入时直接交还调用方，不产生查询。 */
    @Test
    void missingBusinessInputEndsWithoutExecution() {
        when(model.call(any(Prompt.class))).thenReturn(reply(
                "{\"decision\":\"NEED_INFO\",\"message\":\"请提供目标仓库\"}"));

        assertThat(operations.generate(operations.prepare(input("FULL"))))
                .containsEntry("status", "NEED_INFO").containsEntry("route", "end");
        verifyNoInteractions(executor, datasets);
    }

    /** 模型坏格式也消耗预算，不能无限生成。 */
    @Test
    void malformedOutputRetriesWithinTheSameAttemptBudget() {
        when(model.call(any(Prompt.class))).thenReturn(reply("不是JSON"));
        var prepared = operations.prepare(input("LIMITED"));

        var first = operations.generate(prepared);
        var second = operations.generate(first);
        var third = operations.generate(second);

        assertThat(first).containsEntry("route", "retry");
        assertThat(second).containsEntry("route", "retry");
        assertThat(third).containsEntry("attempt", 3).containsEntry("status", "FAILED").containsEntry("route", "end");
        verify(model, times(3)).call(any(Prompt.class));
    }

    /** 恢复到生成节点也不能把旧授权 Schema 再发送给模型。 */
    @Test
    void generationStopsBeforeCallingModelAfterAccountRevocation() {
        var work = operations.prepare(input("LIMITED"));
        when(users.findByUserId(7L)).thenReturn(null);

        assertThat(operations.generate(work)).containsEntry("status", "FAILED");
        verifyNoInteractions(model, executor, datasets);
    }

    /** Schema 外字段由生成模型修正，守卫拒绝后不能执行数据库查询。 */
    @Test
    void unauthorizedColumnStopsBeforeDatabaseExecution() {
        var work = executionWork("SELECT secret FROM stock", "LIMITED");

        assertThat(operations.execute(work)).containsEntry("route", "retry")
                .containsEntry("sql", "SELECT secret FROM stock")
                .satisfies(result -> assertThat(result.get("feedback").toString()).contains("secret"));
        verifyNoInteractions(executor, datasets, model);
    }

    /** 守卫指出不可用字段后，模型收到上次 SQL 和反馈，修正结果仍需经过守卫。 */
    @Test
    void correctsUnavailableColumnUsingPreviousSqlAndGuardFeedback() {
        String invalidSql = "SELECT id, missing FROM stock";
        SqlGuard guard = spy(new DruidSqlGuard());
        var nodes = operations(guard);
        when(model.call(any(Prompt.class))).thenReturn(
                reply("{\"decision\":\"QUERY\",\"sql\":\"" + invalidSql + "\",\"needFullData\":false}"),
                reply("{\"decision\":\"QUERY\",\"sql\":\"" + SQL + "\",\"needFullData\":false}"));
        when(executor.execute(any())).thenReturn(new ExecutionOutcome.Success(
                new QueryResult(COLUMNS, List.of(), 0, true, 1, 1, "hash")));
        when(datasets.stage(any(), any())).thenReturn(record("ds_stage", 0, "STAGED"));

        var rejected = nodes.execute(nodes.generate(nodes.prepare(input("LIMITED"))));
        assertThat(rejected).containsEntry("route", "retry");
        verifyNoInteractions(executor, datasets);
        var corrected = nodes.generate(rejected);
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getContents())
                .contains("previousSql", invalidSql, "feedback", "missing", "stock");

        assertThat(nodes.execute(corrected)).containsEntry("datasetId", "ds_stage").containsEntry("route", "next");
        verify(guard, times(2)).check(anyString(), any(), any(), eq(false));
        verify(executor, times(1)).execute(any());
    }

    /** 连续产生不可用字段时，达到预算后明确停止并给出后续建议。 */
    @Test
    void unavailableColumnStopsAfterRetryBudget() {
        var work = executionWork("SELECT missing FROM stock", "LIMITED");
        work.put("attempt", 3);
        var result = operations.execute(work);
        assertThat(result).containsEntry("status", "FAILED")
                .containsEntry("code", "SQL_RETRY_EXHAUSTED").containsEntry("route", "end")
                .satisfies(value -> assertThat(value.get("guidance").toString()).contains("不要原样重复"));
        verifyNoInteractions(executor, datasets, model);
    }

    /** FULL 不能因为执行器将 LIMIT 读完，就被误判为取得全部数据。 */
    @Test
    void fullRequirementRejectsTopLevelLimit() {
        var work = executionWork(SQL + " LIMIT 10", "FULL");

        assertThat(operations.execute(work)).containsEntry("route", "retry");
        verifyNoInteractions(executor, datasets);
    }

    /** 最外层无 LIMIT 也不能遗漏内层取样，这是业务完整性检查。 */
    @Test
    void fullRequirementRejectsNestedLimit() {
        String sql = "SELECT id FROM stock WHERE id IN (SELECT id FROM stock LIMIT 1)";
        SqlGuard acceptingGuard = mock(SqlGuard.class);
        when(acceptingGuard.check(eq(sql), any(), any(), eq(true))).thenReturn(new GuardResult.Ok(
                new GuardedSql(sql, Set.of("stock"), null, List.of(), null, null)));
        var checkedOperations = operations(acceptingGuard);

        assertThat(checkedOperations.execute(executionWork(sql, "FULL"))).containsEntry("route", "retry");
        verifyNoInteractions(executor, datasets);
    }

    /** 结果超出总上限时直接返回失败，不暂存或发布不完整结果。 */
    @Test
    void fullResultAboveTotalLimitIsNotStaged() {
        when(executor.execute(any())).thenReturn(new ExecutionOutcome.LimitExceeded(5000));
        var output = operations.execute(executionWork(SQL, "FULL"));
        assertThat(output).containsEntry("status", "FAILED")
                .containsEntry("code", "SQL_RESULT_LIMIT_EXCEEDED").containsEntry("route", "end")
                .satisfies(result -> assertThat(result.get("message").toString()).contains("5000", "完整"))
                .satisfies(result -> assertThat(result.get("guidance").toString()).contains("拆分", "完整"));
        verifyNoInteractions(datasets);
    }

    /** 明细游标提前触及分页上限也不能暂存部分结果。 */
    @Test
    void incompleteCursorResultIsNotStaged() {
        when(executor.execute(any())).thenReturn(new ExecutionOutcome.IncompleteResult());
        var output = operations.execute(executionWork(SQL, "FULL"));
        assertThat(output).containsEntry("status", "FAILED")
                .containsEntry("code", "SQL_RESULT_LIMIT_EXCEEDED");
        verifyNoInteractions(datasets);
    }

    /** 只有明确的语法错误允许模型修正，连接和认证等故障直接停止。 */
    @ParameterizedTest
    @EnumSource(SqlErrorKind.class)
    void executionErrorsRespectTheirRetryMeaning(SqlErrorKind kind) {
        when(executor.execute(any())).thenReturn(new ExecutionOutcome.Failed(kind, "给模型的说明", "给用户的说明"));

        var output = operations.execute(executionWork(SQL, "LIMITED"));

        assertThat(output).containsEntry("route", kind == SqlErrorKind.SYNTAX ? "retry" : "end");
        verifyNoInteractions(datasets);
    }

    /** 数据只进入 DatasetManager，结果行不会随工作状态进入检查点。 */
    @Test
    void successfulExecutionStagesDataAndReturnsOnlyItsReference() throws Exception {
        var rows = List.<List<Object>>of(List.of(1L, "PRIVATE_ROW_VALUE"));
        var result = new QueryResult(COLUMNS, rows, 1, true, 1, 1, "hash");
        when(executor.execute(any())).thenReturn(new ExecutionOutcome.Success(result));
        when(datasets.stage(any(), same(result))).thenReturn(record("ds_stage", 1, "STAGED"));

        var output = operations.execute(executionWork(SQL, "LIMITED"));

        assertThat(output).containsEntry("datasetId", "ds_stage").containsEntry("rowCount", 1);
        assertThat(mapper.writeValueAsString(output)).doesNotContain("PRIVATE_ROW_VALUE", "sampleRows", "\"rows\"");
        verify(datasets).stage(argThat(request -> request.userId() == 7L && request.conversationId() == 9L
                && request.sqlRunId().equals("sql_run_test") && request.attempt() == 1), same(result));
    }

    /** 正确查询的空结果可通过，且采用发布服务返回的正式引用。 */
    @Test
    void emptyResultCanPassAndUsesApprovedDatasetId() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(reply("{\"decision\":\"PASS\",\"message\":\"当前范围没有库存记录\"," + DESCRIPTION_JSON + "}"));
        when(datasets.approve(eq("ds_stage"), eq(7L), eq(9L), any(SqlDataDescription.class))).thenReturn(record("ds_already_ready", 0, "READY"));

        var output = operations.inspect(inspectionWork(SQL, "FULL", 0));

        assertThat(output).containsEntry("status", "SUCCESS").containsEntry("datasetId", "ds_already_ready")
                .containsEntry("businessFullResult", true);
        assertThat(output.get("dataDescription")).isNotNull();
        assertThat(mapper.writeValueAsString(output)).doesNotContain("sampleRows", "\"rows\"");
        verify(datasets, never()).cleanupStaged(anyString(), anyLong(), anyLong());
    }

    /** 模型字段序号重复时不能发布，需返回检查反馈重试。 */
    @Test
    void rejectsDescriptionThatDoesNotMatchResultColumns() {
        String invalid = DESCRIPTION_JSON.replace("\"index\":1", "\"index\":0");
        when(model.call(any(Prompt.class))).thenReturn(reply("{\"decision\":\"PASS\",\"message\":\"通过\"," + invalid + "}"));
        var output = operations.inspect(inspectionWork(SQL, "FULL", 1));
        assertThat(output).containsEntry("route", "retry").doesNotContainKey("datasetId");
        assertThat(output.get("feedback").toString()).contains("字段序号", "第 1 列", "序号错误");
        verify(datasets, never()).approve(anyString(), anyLong(), anyLong(), any());
    }

    /** 模型不再抄写列名，发布时采用查询返回的实际别名。 */
    @Test
    void inspectionUsesActualLabelsInsteadOfModelNames() {
        var columns = List.of(new SqlQueryColumn("product_code", "产品编码", Types.VARCHAR, "VARCHAR", false));
        var staged = new DatasetRecord("ds_stage", "sql_run_test", 7, 9, "商品", columns, 0, true, "STAGED", null, null);
        when(datasets.get("ds_stage", 7L, 9L)).thenReturn(staged);
        // 即使模型额外填写物理列名，也由程序采用实际结果的中文别名。
        when(model.call(any(Prompt.class))).thenReturn(reply("{\"decision\":\"PASS\",\"message\":\"通过\",\"dataDescription\":{\"content\":\"商品\",\"grain\":\"每个商品\",\"fields\":[{\"index\":0,\"name\":\"product_code\",\"meaning\":\"商品编码\"}],\"limitations\":\"\"}}"));
        when(datasets.approve(eq("ds_stage"), eq(7L), eq(9L), any(SqlDataDescription.class))).thenReturn(record("ds_stage", 0, "READY"));

        assertThat(operations.inspect(inspectionWork(SQL, "LIMITED", 0))).containsEntry("status", "SUCCESS");
        verify(datasets).approve(eq("ds_stage"), eq(7L), eq(9L), argThat(description ->
                description.fields().equals(List.of(new SqlDataField(0, "产品编码", "商品编码")))));
    }

    /** 无效 JSON 与字段描述错误应提供不同的重试原因。 */
    @Test
    void reportsMalformedInspectionJsonWithoutRawOutput() {
        when(model.call(any(Prompt.class))).thenReturn(reply("not-json-private-value"));
        var output = operations.inspect(inspectionWork(SQL, "FULL", 1));
        assertThat(output.get("feedback").toString()).contains("不是有效的 JSON").doesNotContain("private-value");
        assertThat(output.get("retryDiagnostic")).isEqualTo("模型正文不是有效的 JSON 对象");
    }

    /** 业务检查不通过只撤销引用，暂存保留以支持检查点提交前的崩溃恢复。 */
    @Test
    void rejectedResultClearsAllResultFieldsBeforeRetry() {
        when(model.call(any(Prompt.class))).thenReturn(reply("{\"decision\":\"RETRY\",\"message\":\"结果粒度不符合需求\"}"));

        var output = operations.inspect(inspectionWork(SQL, "LIMITED", 3));

        assertThat(output).containsEntry("route", "retry");
        assertThat(output).doesNotContainKeys("datasetId", "columns", "rowCount", "truncated", "completed",
                "limited", "limit", "limitations", "businessFullResult", "summary");
        verify(datasets, never()).cleanupStaged(anyString(), anyLong(), anyLong());
        verify(datasets, never()).approve(anyString(), anyLong(), anyLong(), any());
    }

    /** 发布后检查点写入失败时，恢复直接采用 READY 结果，不再问模型作新决定。 */
    @Test
    void readyDatasetIsDeliveredWithoutAnotherModelInspection() {
        when(datasets.get("ds_stage", 7L, 9L)).thenReturn(record("ds_stage", 2, "READY"));

        var output = operations.inspect(inspectionWork(SQL, "FULL", 2));

        assertThat(output).containsEntry("status", "SUCCESS").containsEntry("datasetId", "ds_stage")
                .containsEntry("rowCount", 2);
        verifyNoInteractions(model);
        verify(datasets, never()).readStaged(anyString(), anyLong(), anyLong(), anyInt());
        verify(datasets, never()).approve(anyString(), anyLong(), anyLong(), any());
        verify(datasets, never()).cleanupStaged(anyString(), anyLong(), anyLong());
    }

    /** 修正决定未写入检查点时，旧暂存仍可重新检查，不会因文件已删而无法恢复。 */
    @Test
    void rejectedStagingCanBeInspectedAgainFromPreviousCheckpoint() {
        var checkpointWork = inspectionWork(SQL, "LIMITED", 0);
        when(model.call(any(Prompt.class))).thenReturn(
                reply("{\"decision\":\"RETRY\",\"message\":\"需要修正粒度\"}"),
                reply("{\"decision\":\"PASS\",\"message\":\"复核后结果满足需求\"," + DESCRIPTION_JSON + "}"));
        when(datasets.approve(eq("ds_stage"), eq(7L), eq(9L), any(SqlDataDescription.class))).thenReturn(record("ds_stage", 0, "READY"));

        assertThat(operations.inspect(checkpointWork)).containsEntry("route", "retry");
        assertThat(operations.inspect(checkpointWork)).containsEntry("status", "SUCCESS");

        verify(datasets, times(2)).readStaged("ds_stage", 7L, 9L, 5);
        verify(datasets, never()).cleanupStaged(anyString(), anyLong(), anyLong());
    }

    /** 暂停后字段权限撤销，恢复检查不能把样本发送给模型或发布数据。 */
    @Test
    void inspectionRechecksFieldAuthorizationBeforeReadingSamples() {
        var work = inspectionWork(SQL, "LIMITED", 2);
        when(schemas.provideFreshSnapshot(anyList())).thenReturn(snapshot(false));

        assertThat(operations.inspect(work)).containsEntry("status", "FAILED").doesNotContainKey("datasetId");
        verify(datasets, never()).readStaged(anyString(), anyLong(), anyLong(), anyInt());
        verify(datasets, never()).approve(anyString(), anyLong(), anyLong(), any());
        verifyNoInteractions(model);
    }

    /** 截断数据可发布，但不能被标记为业务完整。 */
    @Test
    void incompleteResultCanBePublishedWithExplicitLimitation() {
        var work = inspectionWork(SQL, "FULL", 3);
        var partial = new DatasetRecord("ds_stage", "sql_run_test", 7, 9, "库存明细", COLUMNS, 3, false, "STAGED", null, null);
        var ready = new DatasetRecord("ds_stage", "sql_run_test", 7, 9, "库存明细", COLUMNS, 3, false, "READY", null, null);
        when(datasets.get("ds_stage", 7L, 9L)).thenReturn(partial);
        when(model.call(any(Prompt.class))).thenReturn(reply("{\"decision\":\"PASS\",\"message\":\"已检查部分结果\"," + DESCRIPTION_JSON + "}"));
        when(datasets.approve(eq("ds_stage"), eq(7L), eq(9L), any(SqlDataDescription.class))).thenReturn(ready);
        var output = operations.inspect(work);
        assertThat(output).containsEntry("status", "PARTIAL").containsEntry("truncated", true).containsEntry("businessFullResult", false);
        assertThat(output.get("summary").toString()).contains("完整总行数未知", "不能代表全部数据");
    }

    /** 限量查询成功仍必须保留上限说明，completed 不能代表业务全量。 */
    @Test
    void boundedResultCarriesAnExplicitLimitationAfterApproval() {
        when(executor.execute(any())).thenReturn(new ExecutionOutcome.Success(
                new QueryResult(COLUMNS, List.of(), 0, true, 1, 1, "hash")));
        when(datasets.stage(any(), any())).thenReturn(record("ds_stage", 0, "STAGED"));
        when(model.call(any(Prompt.class))).thenReturn(reply("{\"decision\":\"PASS\",\"message\":\"已取得符合条件的结果\"," + DESCRIPTION_JSON + "}"));
        when(datasets.approve(eq("ds_stage"), eq(7L), eq(9L), any(SqlDataDescription.class))).thenReturn(record("ds_stage", 0, "READY"));

        var output = operations.inspect(operations.execute(executionWork(SQL + " LIMIT 10", "LIMITED")));

        assertThat(output).containsEntry("limited", true).containsEntry("limit", 10).containsEntry("businessFullResult", false);
        assertThat(output.get("summary").toString()).contains("最多 10 行", "不代表全部记录");
    }

    /** 创建真实节点，单独替换守卫可隔离完整性与授权规则的职责。 */
    private TestSqlOperations operations(SqlGuard guard) {
        return new TestSqlOperations(schemas, knowledge, model, mapper, guard, executor,
                new AiSqlProperties(null, true, 200, 5000, 10, 10), users, datasets, 3);
    }

    /** 构造可信入口状态，模型没有写入身份或权限的通道。 */
    private Map<String, Object> input(String dataRequirement) {
        var work = new LinkedHashMap<String, Object>();
        work.put("sqlRunId", "sql_run_test");
        work.put("userId", 7L);
        work.put("conversationId", 9L);
        work.put("requirement", Map.of("goal", "库存明细", "grain", "库存记录",
                "expectedFields", List.of("记录ID", "数量"), "purpose", "检查库存", "dataRequirement", dataRequirement));
        return work;
    }

    /** 从已准备的状态构造生成完成边界，方便验证执行前再次授权。 */
    private Map<String, Object> executionWork(String sql, String dataRequirement) {
        var work = operations.prepare(input(dataRequirement));
        work.put("attempt", 1);
        work.put("sql", sql);
        work.put("needFullData", false);
        return work;
    }

    /** 构造检查点可持久化的结果引用，刻意不包含任何行数据。 */
    private Map<String, Object> inspectionWork(String sql, String dataRequirement, int rows) {
        var work = executionWork(sql, dataRequirement);
        work.put("datasetId", "ds_stage");
        work.put("columns", mapper.convertValue(COLUMNS, List.class));
        work.put("rowCount", rows);
        work.put("completed", true);
        work.put("limited", false);
        work.put("limitations", "");
        return work;
    }

    /** qty 可被撤销，测试需要确认节点没有使用准备阶段的旧列权限。 */
    private AuthorizedSchemaSnapshot snapshot(boolean quantityVisible) {
        var columns = quantityVisible
                ? List.of(new AuthorizedSchemaSnapshot.Column("id", false, true), new AuthorizedSchemaSnapshot.Column("qty", false, false))
                : List.of(new AuthorizedSchemaSnapshot.Column("id", false, true));
        return AuthorizedSchemaSnapshot.of("stock(id, qty)",
                List.of(new AuthorizedSchemaSnapshot.Table("stock", "库存", "warehouse", false, "id")), Map.of("stock", columns));
    }

    /** 数据集状态和总行数独立于模型样本。 */
    private DatasetRecord record(String id, int rows, String status) {
        return new DatasetRecord(id, "sql_run_test", 7, 9, "库存明细", COLUMNS,
                rows, true, status, null, "READY".equals(status) ? DESCRIPTION : null);
    }

    /** 构造供应商返回的真实 Spring AI 响应结构。 */
    private ChatResponse reply(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }
}
