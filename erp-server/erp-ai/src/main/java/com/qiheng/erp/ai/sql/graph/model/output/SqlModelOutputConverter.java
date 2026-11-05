package com.qiheng.erp.ai.sql.graph.model.output;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.converter.BeanOutputConverter;

import java.util.Set;

/** 使用 Spring AI 输出转换器生成格式提示，并校验两个模型节点的分支协议。 */
public final class SqlModelOutputConverter {
    private final ObjectMapper mapper;
    private final BeanOutputConverter<SqlQueryOutput> query;
    private final BeanOutputConverter<SqlNeedInfoOutput> needInfo;
    private final BeanOutputConverter<SqlInspectionOutput> inspection;

    /** 创建各分支的官方输出转换器，复用项目的 JSON 配置。 */
    public SqlModelOutputConverter(ObjectMapper mapper) {
        this.mapper = mapper.copy()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.query = new BeanOutputConverter<>(SqlQueryOutput.class, this.mapper);
        this.needInfo = new BeanOutputConverter<>(SqlNeedInfoOutput.class, this.mapper);
        this.inspection = new BeanOutputConverter<>(SqlInspectionOutput.class, this.mapper);
    }

    /** 两个输出分支各自使用官方生成的 Schema，不能要求同一对象同时满足两份 Schema。 */
    public String generationFormat() {
        return "只返回一个 JSON 对象，根据 decision 严格选择以下两种格式之一：\n"
                + "decision 为 QUERY 时：\n" + query.getJsonSchema()
                + "\ndecision 为 NEED_INFO 时：\n" + needInfo.getJsonSchema();
    }

    /** 结果检查只有一种结构，可直接使用官方的完整格式提示。 */
    public String inspectionFormat() {
        return inspection.getFormat() + "\n仅当 decision 为 PASS 时必须提供 dataDescription；RETRY 和 FAILED 可省略或填 null。";
    }

    /** 先检查真实 JSON 类型，再由官方转换器反序列化对应分支。 */
    public SqlGenerationOutput readGeneration(String text) {
        JsonNode node = object(text);
        String decision = requiredText(node, "decision");
        if ("QUERY".equals(decision)) {
            requiredText(node, "sql");
            if (!node.path("needFullData").isBoolean()) {
                throw new InvalidSqlModelOutput("QUERY 需要布尔值 needFullData");
            }
            return convert(query, text);
        }
        if ("NEED_INFO".equals(decision)) {
            requiredText(node, "message");
            return convert(needInfo, text);
        }
        throw new InvalidSqlModelOutput("decision 必须为 QUERY 或 NEED_INFO");
    }

    /** 检查分支枚举与说明字段，再转换为明确类型。 */
    public SqlInspectionOutput readInspection(String text) {
        JsonNode node = object(text);
        String decision = requiredText(node, "decision");
        requiredText(node, "message");
        if (!Set.of("PASS", "RETRY", "FAILED").contains(decision)) {
            throw new InvalidSqlModelOutput("decision 必须为 PASS、RETRY 或 FAILED");
        }
        if ("PASS".equals(decision)) {
            JsonNode description = node.path("dataDescription");
            if (!description.isObject()) {
                throw new InvalidSqlModelOutput("PASS 需要 dataDescription 对象");
            }
            requiredText(description, "content");
            requiredText(description, "grain");
            if (!description.path("limitations").isTextual()) {
                throw new InvalidSqlModelOutput("dataDescription.limitations 必须为文本");
            }
            JsonNode fields = description.path("fields");
            if (!fields.isArray() || fields.isEmpty()) {
                throw new InvalidSqlModelOutput("dataDescription.fields 必须为非空数组");
            }
            for (JsonNode field : fields) {
                if (!field.isObject() || !field.path("index").isIntegralNumber() || !field.path("index").canConvertToInt()) {
                    throw new InvalidSqlModelOutput("字段 index 必须为整数");
                }
                requiredText(field, "meaning");
            }
        }
        return convert(inspection, text);
    }

    /** 无效正文属于格式错误；模型网络或存储错误不在这里捕获。 */
    private JsonNode object(String text) {
        if (text == null || text.isBlank()) {
            throw new InvalidSqlModelOutput("模型正文为空");
        }
        try {
            JsonNode node = mapper.readTree(text);
            if (node == null || !node.isObject()) {
                throw new InvalidSqlModelOutput("模型正文必须是 JSON 对象");
            }
            return node;
        } catch (JsonProcessingException error) {
            throw new InvalidSqlModelOutput("模型正文不是有效的 JSON 对象");
        }
    }

    /** 校验必需字段为非空字符串，避免 Jackson 隐式转换改变协议。 */
    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new InvalidSqlModelOutput("缺少非空文本字段 " + field);
        }
        return value.asText();
    }

    /** 官方转换器将 JSON 解析异常包装为 RuntimeException，只转化该类格式错误。 */
    private <T> T convert(BeanOutputConverter<T> converter, String text) {
        try {
            return converter.convert(text);
        } catch (RuntimeException error) {
            if (error.getCause() instanceof JsonProcessingException) {
                throw new InvalidSqlModelOutput("模型字段类型不符合输出格式");
            }
            throw error;
        }
    }
}
