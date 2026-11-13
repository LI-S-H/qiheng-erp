package com.qiheng.erp.ai.sql.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.domain.model.AuthorizedSchemaSnapshot;
import com.qiheng.erp.ai.knowledge.KnowledgeProvider;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.sql.execution.ReadOnlySqlExecutor;
import com.qiheng.erp.ai.sql.guard.DruidSqlGuard;
import com.qiheng.erp.ai.sql.schema.SchemaProvider;
import com.qiheng.erp.security.domain.dto.LoginUser;
import com.qiheng.erp.security.service.LoginUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 核实取数子图两个模型节点写入的智能体流水。
 *
 * <p>SQL 生成与结果校验是两个独立智能体，各自只记录本轮的输入与产出；
 * 授权范围用指纹表示，不落原文。</p>
 */
class SqlNodeTraceRecordingTest {
    private static final List<com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn> COLUMNS = List.of(
            new com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn("id", "id", Types.BIGINT, "BIGINT", false),
            new com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn("qty", "qty", Types.BIGINT, "BIGINT", false));

    private final ObjectMapper mapper = new ObjectMapper();
    private final SchemaProvider schemas = mock(SchemaProvider.class);
    private final KnowledgeProvider knowledge = mock(KnowledgeProvider.class);
    private final ChatModel model = mock(ChatModel.class);
    private final ReadOnlySqlExecutor executor = mock(ReadOnlySqlExecutor.class);
    private final LoginUserService users = mock(LoginUserService.class);
    private final DatasetManager datasets = mock(DatasetManager.class);
    private final AiAgentTraceMapper traces = mock(AiAgentTraceMapper.class);
    private TestSqlOperations operations;

    /** 模拟真实账户权限与授权快照，节点必须每次重新读取。 */
    @BeforeEach
    void configureDependencies() {
        var user = new LoginUser();
        user.setUserId(7L);
        user.setPermissionCodes(List.of("ai:assistant:use", "warehouse:query"));
        when(users.findByUserId(7L)).thenReturn(user);
        when(schemas.provideFreshSnapshot(anyList())).thenReturn(snapshot());
        when(knowledge.provide(anyList())).thenReturn("库存数量保存真实值乘以100。");
        when(datasets.readStaged("ds_stage", 7L, 9L, 5)).thenReturn(List.of());
        when(datasets.get("ds_stage", 7L, 9L)).thenReturn(record("ds_stage", 0, "STAGED"));
        operations = new TestSqlOperations(schemas, knowledge, model, mapper, new DruidSqlGuard(), executor,
                new AiSqlProperties(null, true, 200, 5000, 10, 10), users, datasets, 3, traces);
    }

    /** SQL 生成节点写入自己的输入与产出。 */
    @Test
    void generateNodeRecordsItsOwnInputAndOutput() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(reply(
                "{\"decision\":\"QUERY\",\"sql\":\"SELECT id, qty FROM stock\",\"needFullData\":false}"));

        operations.generate(operations.prepare(input("LIMITED")));

        var events = capturedEvents();
        assertThat(events).extracting(TraceEvent::agentType).containsOnly("SQL_GENERATE");
        assertThat(events).extracting(TraceEvent::eventType).containsExactly("INPUT", "MODEL_OUTPUT");
        assertThat(events.get(0).content().path("requirement").path("goal").asText()).isEqualTo("库存明细");
        assertThat(events.get(0).content().path("schemaFingerprint").asText()).isNotBlank();
        assertThat(events.get(0).content().has("schema")).isFalse();
        assertThat(events.get(0).content().has("knowledge")).isFalse();
        assertThat(events.get(1).content().path("sql").asText()).isEqualTo("SELECT id, qty FROM stock");
    }

    /** 结果校验节点写入自己的输入与产出，与生成节点分开。 */
    @Test
    void inspectNodeRecordsItsOwnInputAndOutput() throws Exception {
        when(model.call(any(Prompt.class))).thenReturn(reply(
                "{\"decision\":\"RETRY\",\"message\":\"缺少时间范围\"}"));

        var work = operations.prepare(input("LIMITED"));
        work.put("attempt", 1);
        work.put("sql", "SELECT id, qty FROM stock");
        work.put("needFullData", false);
        work.put("datasetId", "ds_stage");
        operations.inspect(work);

        var events = capturedEvents();
        assertThat(events).extracting(TraceEvent::agentType).containsOnly("SQL_INSPECT");
        assertThat(events).extracting(TraceEvent::eventType).containsExactly("INPUT", "MODEL_OUTPUT");
        assertThat(events.get(0).content().path("sql").asText()).isEqualTo("SELECT id, qty FROM stock");
        assertThat(events.get(0).content().has("schema")).isFalse();
        assertThat(events.get(1).content().path("decision").asText()).isEqualTo("RETRY");
        assertThat(events.get(1).content().path("message").asText()).isEqualTo("缺少时间范围");
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

    /** 授权快照含可见范围指纹。 */
    private AuthorizedSchemaSnapshot snapshot() {
        var columns = List.of(new AuthorizedSchemaSnapshot.Column("id", false, true),
                new AuthorizedSchemaSnapshot.Column("qty", false, false));
        return AuthorizedSchemaSnapshot.of("stock(id, qty)",
                List.of(new AuthorizedSchemaSnapshot.Table("stock", "库存", "warehouse", false, "id")),
                Map.of("stock", columns));
    }

    /** 数据集状态和总行数独立于模型样本。 */
    private DatasetRecord record(String id, int rows, String status) {
        return new DatasetRecord(id, "sql_run_test", 7, 9, "库存明细", COLUMNS, rows, true, status, null, null);
    }

    /** 构造供应商返回的真实 Spring AI 响应结构。 */
    private ChatResponse reply(String json) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(json))));
    }

    /** 读取写入的流水事件；参数顺序与 Mapper 一致。 */
    private List<TraceEvent> capturedEvents() throws Exception {
        var taskId = ArgumentCaptor.forClass(String.class);
        var agentType = ArgumentCaptor.forClass(String.class);
        var eventType = ArgumentCaptor.forClass(String.class);
        var content = ArgumentCaptor.forClass(String.class);
        verify(traces, atLeastOnce()).insert(anyLong(), anyLong(), taskId.capture(), agentType.capture(),
                eventType.capture(), content.capture());
        var events = new ArrayList<TraceEvent>();
        for (int index = 0; index < eventType.getAllValues().size(); index++) {
            events.add(new TraceEvent(taskId.getAllValues().get(index), agentType.getAllValues().get(index),
                    eventType.getAllValues().get(index), mapper.readTree(content.getAllValues().get(index))));
        }
        return events;
    }

    /** 一条流水事件。 */
    private record TraceEvent(String taskId, String agentType, String eventType, JsonNode content) {
    }
}
