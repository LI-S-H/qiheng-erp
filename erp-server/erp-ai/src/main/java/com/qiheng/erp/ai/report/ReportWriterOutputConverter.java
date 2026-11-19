package com.qiheng.erp.ai.report;

import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.converter.BeanOutputConverter;
import java.util.List;

/** 生成报告输出格式说明，并按分支校验最终答复。 */
public final class ReportWriterOutputConverter {
    public sealed interface Output permits Completed, Unavailable {}

    public record Completed(
            @JsonPropertyDescription("固定填写 REPORT，表示报告已完成") String decision,
            @JsonPropertyDescription("本次报告标题，简洁概括报告内容") String title,
            @JsonPropertyDescription("完整报告正文，从主要结论或正文首段开始，章节从二级标题起") String content) implements Output {
        /** 保留既有内部调用的默认标题；模型输出仍需显式提供有效标题。 */
        public Completed(String decision, String content) {
            this(decision, "报告", content);
        }
    }

    public record Reference(
            @JsonPropertyDescription("资料类型：dataset 或 analysisResult") String type,
            @JsonPropertyDescription("不可用资料的真实 ID") String id) {}

    public record Unavailable(
            @JsonPropertyDescription("固定填写 REFERENCE_UNAVAILABLE，表示所需资料不可用") String decision,
            @JsonPropertyDescription("说明资料无法读取以及对报告的影响") String message,
            @JsonPropertyDescription("不可用资料的类型和 ID 列表") List<Reference> unavailableReferences) implements Output {}

    private final ObjectMapper mapper;
    private final BeanOutputConverter<Completed> completed;
    private final BeanOutputConverter<Unavailable> unavailable;

    public ReportWriterOutputConverter(ObjectMapper mapper) {
        this.mapper = mapper.copy().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.completed = new BeanOutputConverter<>(Completed.class, this.mapper);
        this.unavailable = new BeanOutputConverter<>(Unavailable.class, this.mapper);
    }

    /** 明确两种互斥分支，避免模型误以为必须同时满足两个结构。 */
    public String outputFormat() {
        return """
                # 最终输出格式
                工具调用期间正常调用工具；完成任务时只输出一个 JSON 对象，不要附加其他文字。
                资料可用且报告完成时使用 REPORT，content 填写完整正文：
                """ + completed.getJsonSchema() + """

                报告所需资料不可用，导致关键事实无法核对时使用 REFERENCE_UNAVAILABLE，
                不得编造资料内容；说明不可用资料及其影响：
                """ + unavailable.getJsonSchema();
    }

    /** 只允许准确的分支及其必要字段，供 Hook 与服务共同使用。 */
    public Output parse(String answer) {
        try {
            var node = mapper.readTree(answer);
            if (node == null || !node.isObject() || !node.path("decision").isTextual()) {
                throw new InvalidOutput("报告输出格式错误：请填写有效的 decision。");
            }
            String decision = node.path("decision").asText();
            if ("REPORT".equals(decision)) {
                if (!node.path("title").isTextual() || node.path("title").asText().isBlank()
                        || node.path("title").asText().length() > 200) {
                    throw new InvalidOutput("报告输出格式错误：REPORT 的 title 必须为不超过 200 字的非空文字。");
                }
                if (!node.path("content").isTextual() || node.path("content").asText().isBlank()) {
                    throw new InvalidOutput("报告输出格式错误：REPORT 的 content 不能为空。");
                }
                return completed.convert(answer);
            }
            if ("REFERENCE_UNAVAILABLE".equals(decision)) {
                if (!node.path("message").isTextual() || node.path("message").asText().isBlank()
                        || !node.path("unavailableReferences").isArray() || node.path("unavailableReferences").isEmpty()) {
                    throw new InvalidOutput("报告输出格式错误：请说明资料不可用原因和资料列表。");
                }
                for (var item : node.path("unavailableReferences")) {
                    if (!item.path("type").isTextual() || !List.of("dataset", "analysisResult").contains(item.path("type").asText())
                            || !item.path("id").isTextual() || item.path("id").asText().isBlank()) {
                        throw new InvalidOutput("报告输出格式错误：不可用资料需填写有效类型和 ID。");
                    }
                }
                return unavailable.convert(answer);
            }
            throw new InvalidOutput("报告输出格式错误：decision 必须为 REPORT 或 REFERENCE_UNAVAILABLE。");
        } catch (InvalidOutput error) {
            throw error;
        } catch (Exception error) {
            throw new InvalidOutput("报告输出的 JSON 格式错误，请重新生成。");
        }
    }

    /** 报告输出格式错误，不表示模型或资料调用失败。 */
    public static final class InvalidOutput extends IllegalArgumentException {
        public InvalidOutput(String message) {
            super(message);
        }
    }
}
