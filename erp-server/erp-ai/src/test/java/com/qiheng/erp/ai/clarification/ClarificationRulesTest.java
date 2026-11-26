package com.qiheng.erp.ai.clarification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.clarification.AskUserTool.Question;
import com.qiheng.erp.ai.clarification.AskUserTool.QuestionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 追问参数与回答校验规则测试。
 *
 * @author Li
 * @since 2026-11-26
 */
class ClarificationRulesTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** 合法单选题可以通过，并解析出问题对象。 */
    @Test
    void acceptsValidQuestions() {
        var result = ClarificationRules.validateQuestions("""
                {"questions":[{"id":"warehouse","question":"查哪个仓库？","type":"SINGLE_CHOICE","options":["一号仓","二号仓"]}]}
                """, mapper);

        assertThat(result.valid()).isTrue();
        assertThat(result.questions()).hasSize(1);
        assertThat(result.questions().getFirst().id()).isEqualTo("warehouse");
        assertThat(result.questions().getFirst().options()).containsExactly("一号仓", "二号仓");
    }

    /** JSON 解析失败时给出具体路径和解析原因。 */
    @Test
    void reportsJsonParseError() {
        var result = ClarificationRules.validateQuestions("{\"questions\":[}", mapper);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().path()).isEqualTo("$");
        assertThat(result.errors().getFirst().message()).contains("JSON 解析失败");
    }

    /** questions 缺失、为空或不是数组时都要报错。 */
    @Test
    void rejectsMissingOrEmptyQuestions() {
        assertThat(ClarificationRules.validateQuestions("{}", mapper).errors())
                .anyMatch(error -> error.path().equals("questions"));
        assertThat(ClarificationRules.validateQuestions("{\"questions\":[]}", mapper).errors())
                .anyMatch(error -> error.message().contains("至少需要一个"));
        assertThat(ClarificationRules.validateQuestions("{\"questions\":{}}", mapper).errors())
                .anyMatch(error -> error.message().contains("必须是数组"));
    }

    /** 问题 ID 重复、正文为空、类型非法都要精确到字段。 */
    @Test
    void reportsQuestionFieldErrors() {
        var result = ClarificationRules.validateQuestions("""
                {"questions":[
                  {"id":"same","question":"","type":"OTHER","options":[]},
                  {"id":"same","question":"第二题","type":"TEXT","options":[]}
                ]}
                """, mapper);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.path().equals("questions[1].id") && error.message().contains("不能重复"));
        assertThat(result.errors()).anyMatch(error -> error.path().equals("questions[0].question") && error.message().contains("不能为空"));
        assertThat(result.errors()).anyMatch(error -> error.path().equals("questions[0].type") && error.message().contains("SINGLE_CHOICE"));
    }

    /** 选择题没有选项、选项重复、TEXT 带选项都要报错。 */
    @Test
    void reportsOptionErrors() {
        var single = ClarificationRules.validateQuestions("""
                {"questions":[{"id":"q1","question":"选一个","type":"SINGLE_CHOICE","options":[]}]}
                """, mapper);
        assertThat(single.errors()).anyMatch(error -> error.path().equals("questions[0].options") && error.message().contains("至少需要一个"));

        var duplicate = ClarificationRules.validateQuestions("""
                {"questions":[{"id":"q1","question":"选一个","type":"SINGLE_CHOICE","options":["A","A"]}]}
                """, mapper);
        assertThat(duplicate.errors()).anyMatch(error -> error.path().equals("questions[0].options[1]") && error.message().contains("不能重复"));

        var textWithOptions = ClarificationRules.validateQuestions("""
                {"questions":[{"id":"q1","question":"补充说明","type":"TEXT","options":["A"]}]}
                """, mapper);
        assertThat(textWithOptions.errors()).anyMatch(error -> error.path().equals("questions[0].options") && error.message().contains("TEXT"));
    }

    /** 合法答案可以通过。 */
    @Test
    void acceptsValidAnswers() {
        List<Question> questions = List.of(
                new Question("warehouse", "查哪个仓库？", QuestionType.SINGLE_CHOICE, List.of("一号仓", "二号仓")),
                new Question("note", "补充说明", QuestionType.TEXT, List.of()));

        var errors = ClarificationRules.validateAnswers(questions, List.of(
                new ClarificationAnswer("warehouse", List.of("一号仓"), ""),
                new ClarificationAnswer("note", List.of(), "尽快")));

        assertThat(errors).isEmpty();
    }

    /** 少答、未知题号、单选多选、文本题选选项都要报错。 */
    @Test
    void reportsAnswerErrors() {
        List<Question> questions = List.of(
                new Question("warehouse", "查哪个仓库？", QuestionType.SINGLE_CHOICE, List.of("一号仓", "二号仓")),
                new Question("note", "补充说明", QuestionType.TEXT, List.of()),
                new Question("multi", "选择指标", QuestionType.MULTIPLE_CHOICE, List.of("库存", "金额")));

        var errors = ClarificationRules.validateAnswers(questions, List.of(
                new ClarificationAnswer("warehouse", List.of("一号仓", "二号仓"), ""),
                new ClarificationAnswer("note", List.of("库存"), ""),
                new ClarificationAnswer("unknown", List.of(), "")));

        assertThat(errors).anyMatch(error -> error.path().equals("answers[warehouse]") && error.message().contains("只能选择一个"));
        assertThat(errors).anyMatch(error -> error.path().equals("answers[note]") && error.message().contains("文本题不能选择"));
        assertThat(errors).anyMatch(error -> error.path().equals("answers[multi]") && error.message().contains("未回答"));
        assertThat(errors).anyMatch(error -> error.path().equals("answers[unknown]") && error.message().contains("不匹配"));
    }

    /** 选项越界和自定义内容过长要报错。 */
    @Test
    void reportsOptionAndLengthErrors() {
        List<Question> questions = List.of(
                new Question("warehouse", "查哪个仓库？", QuestionType.SINGLE_CHOICE, List.of("一号仓")));

        var errors = ClarificationRules.validateAnswers(questions, List.of(
                new ClarificationAnswer("warehouse", List.of("三号仓"), "x".repeat(ClarificationRules.MAX_ANSWER_LENGTH + 1))));

        assertThat(errors).anyMatch(error -> error.message().contains("选项不在候选中"));
        assertThat(errors).anyMatch(error -> error.message().contains("补充内容不能超过"));
    }

    /** 错误数量超过上限时只返回前 10 条。 */
    @Test
    void limitsErrorCount() {
        StringBuilder json = new StringBuilder("{\"questions\":[");
        for (int index = 0; index < 15; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"\",\"question\":\"\",\"type\":\"TEXT\",\"options\":[]}");
        }
        json.append("]}");

        var result = ClarificationRules.validateQuestions(json.toString(), mapper);

        assertThat(result.errors()).hasSize(ClarificationRules.MAX_ERRORS);
    }

    /** 首尾空白在前后端统一裁剪，避免合法选项被误判。 */
    @Test
    void trimsWhitespaceInQuestionsAndAnswers() {
        var validation = ClarificationRules.validateQuestions("""
                {"questions":[{"id":" warehouse ","question":" 查哪个仓库？ ","type":"SINGLE_CHOICE","options":[" 一号仓 "]}]}
                """, mapper);
        assertThat(validation.valid()).isTrue();
        assertThat(validation.questions().getFirst().id()).isEqualTo("warehouse");
        assertThat(validation.questions().getFirst().options()).containsExactly("一号仓");

        var errors = ClarificationRules.validateAnswers(validation.questions(),
                List.of(new ClarificationAnswer(" warehouse ", List.of(" 一号仓 "), "")));
        assertThat(errors).isEmpty();
    }

    /** 错误摘要只拼接前三条，且是中文可读文本。 */
    @Test
    void summarizesReadableMessage() {
        var message = ClarificationRules.summarize(List.of(
                new ClarificationRules.FieldError("questions[0].id", "不能为空"),
                new ClarificationRules.FieldError("questions[0].type", "类型不对"),
                new ClarificationRules.FieldError("questions[1].options", "至少一个"),
                new ClarificationRules.FieldError("questions[2].options", "不会显示")));

        assertThat(message).isEqualTo("questions[0].id：不能为空；questions[0].type：类型不对；questions[1].options：至少一个");
        assertThat(ClarificationRules.summarizeForUser(List.of(
                new ClarificationRules.FieldError("questions[0].id", "不能为空"),
                new ClarificationRules.FieldError("questions[0].type", "类型不对"),
                new ClarificationRules.FieldError("questions[1].options", "至少一个"))))
                .isEqualTo("不能为空；类型不对；至少一个");
    }
}