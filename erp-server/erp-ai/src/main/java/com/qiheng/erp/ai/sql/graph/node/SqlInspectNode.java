package com.qiheng.erp.ai.sql.graph.node;

import com.qiheng.erp.ai.sql.graph.SqlNodeSupport;
import com.qiheng.erp.ai.sql.graph.model.SqlModelClient;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.qiheng.erp.ai.sql.graph.model.output.InvalidSqlModelOutput;
import com.qiheng.erp.ai.sql.graph.model.output.SqlInspectionOutput;
import com.qiheng.erp.ai.sql.graph.model.output.SqlInspectionDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.qiheng.erp.ai.config.properties.AiSqlProperties;
import com.qiheng.erp.ai.dataset.DatasetManager;
import com.qiheng.erp.ai.dataset.DatasetRecord;
import com.qiheng.erp.ai.dataset.SqlDataDescription;
import com.qiheng.erp.ai.dataset.SqlDataField;
import com.qiheng.erp.ai.sql.execution.model.SqlQueryColumn;
import com.qiheng.erp.ai.sql.guard.SqlGuard;
import com.qiheng.erp.ai.sql.guard.model.GuardResult;
import com.qiheng.erp.ai.sql.guard.model.SqlGuardLimits;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.clearResult;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.finish;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.id;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.requiresFullResult;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.schema;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.update;
import static com.qiheng.erp.ai.sql.graph.SqlNodeSupport.work;

/** 检查结果及发布数据集。 */
public final class SqlInspectNode implements NodeAction {
    private static final Logger log = LoggerFactory.getLogger(SqlInspectNode.class);
    private static final int INSPECTION_SAMPLE_ROWS = 5;
    private final SqlNodeSupport support;
    private final SqlModelClient modelClient;
    private final SqlGuard guard;
    private final AiSqlProperties properties;
    private final DatasetManager datasets;
    private final ObjectMapper mapper;
    private final String inspectPrompt;

    /** 检查结果使用当前授权、模型判断和暂存数据集。 */
    public SqlInspectNode(SqlNodeSupport support, SqlModelClient modelClient, SqlGuard guard,
                          AiSqlProperties properties, DatasetManager datasets, ObjectMapper mapper) {
        this.support = support;
        this.modelClient = modelClient;
        this.guard = guard;
        this.properties = properties;
        this.datasets = datasets;
        this.mapper = mapper;
        this.inspectPrompt = SqlModelClient.loadPrompt("prompts/sql/inspect.md");
    }

    /** 执行节点并返回 State 的更新。 */
    @Override
    public Map<String, Object> apply(OverAllState state) {
        Map<String, Object> work = work(state);
        String datasetId = (String) work.get("datasetId");
        long userId = id(work, "userId");
        long conversationId = id(work, "conversationId");
        // 恢复可能直接进入检查节点：在读取样本和发布之前再次核对当前权限。
        if (!support.refreshAuthorization(work)) {
            clearResult(work);
            return update(work);
        }
        GuardResult authorized = guard.check((String) work.get("sql"), schema(work),
                new SqlGuardLimits(properties.maxTotalRows()), requiresFullResult(work));
        if (authorized instanceof GuardResult.Rejected) {
            clearResult(work);
            return update(finish(work, "FAILED", "当前授权或取数规则已不允许读取本次结果，取数已停止。"));
        }
        DatasetRecord stored = datasets.get(datasetId, userId, conversationId);
        // 发布与 Redis 检查点不在同一事务：已发布结果恢复时直接交付，不再次询问模型。
        if ("READY".equals(stored.status())) {
            return update(approvedResult(work, stored, "已恢复通过检查的数据集，共 " + stored.rowCount() + " 行。"));
        }
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("requirement", work.get("requirement"));
        input.put("supplements", work.get("supplements"));
        input.put("currentDate", work.get("currentDate"));
        input.put("timeZone", work.get("timeZone"));
        input.put("schema", schema(work).schemaText());
        input.put("knowledge", work.get("knowledge"));
        input.put("sql", work.get("sql"));
        input.put("needFullData", work.get("needFullData"));
        input.put("limitations", stored.completed() ? work.get("limitations") : stored.completenessMessage());
        input.put("completed", stored.completed());
        input.put("columns", stored.columns());
        input.put("rowCount", stored.rowCount());
        // 样本仅作为本次检查的模型输入，不返回节点更新，不进入 Redis 检查点。
        input.put("sampleRows", datasets.readStaged(datasetId, userId, conversationId, INSPECTION_SAMPLE_ROWS));
        work.remove("retryDiagnostic");
        try {
            SqlInspectionOutput answer = modelClient.inspect(inspectPrompt, input);
            String decision = answer.decision();
            String message = answer.message();
            if ("PASS".equals(decision)) {
                SqlDataDescription description = completeDescription(answer.dataDescription(), stored.columns(), (String) work.get("sqlRunId"), datasetId);
                DatasetRecord approved = datasets.approve(datasetId, userId, conversationId, description);
                return update(approvedResult(work, approved, message));
            }
            // 先只撤销工作引用；立即删文件会使尚未提交检查点的恢复找不到输入。
            // 暂存结果目前没有自动物理清理，保留期和清理由数据集生命周期另行处理。
            clearResult(work);
            if ("FAILED".equals(decision)) {
                return update(finish(work, "FAILED", message));
            }
            return update(support.retry(work, message, "查询结果未能通过业务检查。", "inspect"));
        } catch (InvalidSqlModelOutput error) {
            clearResult(work);
            // 仅记录校验器产生的字段级原因，不记录模型原文和业务数据。
            String reason = error.getMessage();
            work.put("retryDiagnostic", reason);
            return update(support.retry(work, "结果检查输出无效：" + reason, "查询结果未能通过业务检查。", "inspect"));
        } catch (JsonProcessingException error) {
            clearResult(work);
            work.put("retryDiagnostic", "检查输入序列化失败");
            return update(support.retry(work, "结果检查输入无法序列化。", "查询结果未能通过业务检查。", "inspect"));
        }
    }

    /** 按实际结果补齐列标签，避免模型重复抄写名称导致误判。 */
    private SqlDataDescription completeDescription(SqlInspectionDescription description, List<SqlQueryColumn> columns, String runId, String datasetId) {
        if (description.fields().size() != columns.size()) {
            log.warn("SQL 数据说明字段数量不一致 runId={} datasetId={} 期望数量={} 模型数量={}",
                    runId, datasetId, columns.size(), description.fields().size());
            throw new InvalidSqlModelOutput("数据说明字段数量与查询结果不一致");
        }
        var fields = new ArrayList<SqlDataField>(columns.size());
        for (int i = 0; i < columns.size(); i++) {
            var field = description.fields().get(i);
            if (field.index() != i) {
                log.warn("SQL 数据说明字段序号不一致 runId={} datasetId={} 期望序号={} 模型序号={}",
                        runId, datasetId, i, field.index());
                throw new InvalidSqlModelOutput("数据说明的字段序号与查询结果不一致：第 " + i + " 列序号错误，请按 columns 的顺序填写");
            }
            fields.add(new SqlDataField(i, columns.get(i).label(), field.meaning()));
        }
        return new SqlDataDescription(description.content(), description.grain(), fields, description.limitations());
    }

    /** 首次发布与恢复均以正式数据集引用交付。 */
    private Map<String, Object> approvedResult(Map<String, Object> work, DatasetRecord approved, String message) {
        work.put("datasetId", approved.datasetId());
        work.put("columns", mapper.convertValue(approved.columns(), List.class));
        work.put("rowCount", approved.rowCount());
        work.put("completed", approved.completed());
        work.put("dataDescription", mapper.convertValue(approved.dataDescription(), Map.class));
        // completed 只说明执行器完成读取，业务是否全量需另看 limited 与需求。
        work.put("truncated", !approved.completed());
        if (approved.truncationReason() != null) {
            work.put("truncationReason", approved.truncationReason());
        }
        work.put("businessFullResult", approved.completed() && requiresFullResult(work) && !Boolean.TRUE.equals(work.get("limited")));
        String limitations = (String) work.get("limitations");
        if (!approved.completed()) {
            limitations = limitations + " " + approved.completenessMessage();
            work.put("limitations", limitations.trim());
        }
        if (!limitations.isBlank()) {
            message = message + " " + limitations;
        }
        work.put("summary", message);
        return finish(work, approved.completed() ? "SUCCESS" : "PARTIAL", message);
    }
}
