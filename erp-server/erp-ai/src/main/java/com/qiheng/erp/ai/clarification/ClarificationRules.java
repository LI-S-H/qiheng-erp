package com.qiheng.erp.ai.clarification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.clarification.AskUserTool.Question;
import com.qiheng.erp.ai.clarification.AskUserTool.QuestionType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 追问参数与回答的统一校验规则。
 *
 * <p>工具参数校验、HITL 前置校验和答案提交校验共用本类，保证前后端口径一致。</p>
 *
 * @author Li
 * @since 2026-11-26
 */
public final class ClarificationRules {

    /** 单次最多返回的错误条数，避免把过长错误塞回模型或页面。 */
    public static final int MAX_ERRORS = 10;

    /** 问题 ID 最大长度。 */
    public static final int MAX_QUESTION_ID_LENGTH = 64;

    /** 问题正文最大长度。 */
    public static final int MAX_QUESTION_LENGTH = 500;

    /** 单个选项最大长度。 */
    public static final int MAX_OPTION_LENGTH = 200;

    /** 选择题最多选项数。 */
    public static final int MAX_OPTIONS = 20;

    /** 单次追问最多问题数。 */
    public static final int MAX_QUESTIONS = 20;

    /** 单次回答文本最大长度，与前端保持一致。 */
    public static final int MAX_ANSWER_LENGTH = 10_000;

    private ClarificationRules() {
    }

    /** 字段级校验错误，path 使用 JSON 路径，便于模型精确定位。 */
    public record FieldError(String path, String message) {
    }

    /** 问题参数校验结果；有错误时 questions 可能不完整，调用方应以 errors 为准。 */
    public record QuestionValidation(List<Question> questions, List<FieldError> errors) {

        /** 是否全部通过。 */
        public boolean valid() {
            return errors.isEmpty();
        }
    }

    /**
     * 校验模型给出的 questions JSON。
     *
     * @param raw 原始 JSON 字符串
     * @param mapper JSON 解析器
     * @return 解析出的问题和字段级错误
     */
    public static QuestionValidation validateQuestions(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return invalid("$", "追问参数不能为空");
        }
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (JsonProcessingException error) {
            return invalid("$", "JSON 解析失败：" + error.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            return invalid("$", "追问参数必须是 JSON 对象");
        }
        JsonNode questionNodes = root.get("questions");
        if (questionNodes == null || !questionNodes.isArray()) {
            return invalid("questions", "questions 必须是数组");
        }
        if (questionNodes.isEmpty()) {
            return invalid("questions", "至少需要一个问题");
        }

        List<FieldError> errors = new ArrayList<>();
        List<Question> questions = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        if (questionNodes.size() > MAX_QUESTIONS) {
            errors.add(new FieldError("questions", "最多 " + MAX_QUESTIONS + " 个问题"));
        }
        for (int index = 0; index < questionNodes.size(); index++) {
            JsonNode node = questionNodes.get(index);
            int before = errors.size();
            String path = "questions[" + index + "]";
            if (node == null || !node.isObject()) {
                errors.add(new FieldError(path, "必须是对象"));
                continue;
            }
            String id = trim(text(node, "id"));
            if (id == null || id.isBlank()) {
                errors.add(new FieldError(path + ".id", "不能为空"));
            } else if (id.length() > MAX_QUESTION_ID_LENGTH) {
                errors.add(new FieldError(path + ".id", "不能超过 " + MAX_QUESTION_ID_LENGTH + " 个字符"));
            } else if (!ids.add(id)) {
                errors.add(new FieldError(path + ".id", "不能重复：" + id));
            }

            String question = trim(text(node, "question"));
            if (question == null || question.isBlank()) {
                errors.add(new FieldError(path + ".question", "不能为空"));
            } else if (question.length() > MAX_QUESTION_LENGTH) {
                errors.add(new FieldError(path + ".question", "不能超过 " + MAX_QUESTION_LENGTH + " 个字符"));
            }

            QuestionType type = parseType(trim(text(node, "type")));
            if (type == null) {
                errors.add(new FieldError(path + ".type", "必须是 SINGLE_CHOICE、MULTIPLE_CHOICE 或 TEXT"));
            }

            JsonNode optionNodes = node.get("options");
            List<String> options = new ArrayList<>();
            if (optionNodes == null || !optionNodes.isArray()) {
                errors.add(new FieldError(path + ".options", "必须是数组"));
            } else {
                Set<String> seen = new LinkedHashSet<>();
                for (int optionIndex = 0; optionIndex < optionNodes.size(); optionIndex++) {
                    JsonNode optionNode = optionNodes.get(optionIndex);
                    String option = optionNode != null && optionNode.isTextual() ? trim(optionNode.asText()) : null;
                    String optionPath = path + ".options[" + optionIndex + "]";
                    if (option == null || option.isBlank()) {
                        errors.add(new FieldError(optionPath, "不能为空"));
                        continue;
                    }
                    if (option.length() > MAX_OPTION_LENGTH) {
                        errors.add(new FieldError(optionPath, "不能超过 " + MAX_OPTION_LENGTH + " 个字符"));
                        continue;
                    }
                    if (!seen.add(option)) {
                        errors.add(new FieldError(optionPath, "选项不能重复：" + option));
                        continue;
                    }
                    options.add(option);
                }
                if (optionNodes.size() > MAX_OPTIONS) {
                    errors.add(new FieldError(path + ".options", "最多 " + MAX_OPTIONS + " 个选项"));
                }
                if (type != null && type != QuestionType.TEXT && options.isEmpty()) {
                    errors.add(new FieldError(path + ".options", type.name() + " 至少需要一个非空选项"));
                }
                if (type == QuestionType.TEXT && !options.isEmpty()) {
                    errors.add(new FieldError(path + ".options", "TEXT 类型不需要选项，请传空数组"));
                }
            }

            if (errors.size() == before && id != null && question != null && type != null) {
                questions.add(new Question(id, question, type, List.copyOf(options)));
            }
        }
        return new QuestionValidation(List.copyOf(questions), limit(errors));
    }

    /**
     * 校验用户对当前问题的回答。
     *
     * @param questions 当前检查点里的问题
     * @param answers 用户回答
     * @return 字段级错误
     */
    public static List<FieldError> validateAnswers(List<Question> questions, List<ClarificationAnswer> answers) {
        List<FieldError> errors = new ArrayList<>();
        if (answers == null || answers.size() != questions.size()) {
            errors.add(new FieldError("answers", "需要回答 " + questions.size() + " 个问题，实际收到 "
                    + (answers == null ? 0 : answers.size()) + " 个"));
        }
        Map<String, ClarificationAnswer> byId = new HashMap<>();
        if (answers != null) {
            for (ClarificationAnswer answer : answers) {
                if (answer == null || answer.questionId() == null || answer.questionId().isBlank()) {
                    errors.add(new FieldError("answers", "回答缺少问题 ID"));
                    continue;
                }
                String answerId = answer.questionId().trim();
                if (byId.putIfAbsent(answerId, answer) != null) {
                    errors.add(new FieldError("answers[" + answerId + "]", "同一个问题不能重复回答"));
                }
            }
        }
        Set<String> questionIds = new HashSet<>();
        for (Question question : questions) {
            questionIds.add(question.id());
            ClarificationAnswer answer = byId.get(question.id());
            if (answer == null) {
                errors.add(new FieldError("answers[" + question.id() + "]", "未回答"));
                continue;
            }
            validateAnswer(question, answer, errors);
        }
        for (String id : byId.keySet()) {
            if (!questionIds.contains(id)) {
                errors.add(new FieldError("answers[" + id + "]", "回答与当前追问不匹配"));
            }
        }
        return limit(errors);
    }

    /** 把错误压缩成一行中文，供接口 message 使用。 */
    public static String summarize(List<FieldError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (FieldError error : errors.subList(0, Math.min(3, errors.size()))) {
            parts.add(error.path() + "：" + error.message());
        }
        return String.join("；", parts);
    }

    /** 面向用户的错误摘要：只保留可读原因，不展示字段路径。 */
    public static String summarizeForUser(List<FieldError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "";
        }
        return errors.stream()
                .limit(3)
                .map(FieldError::message)
                .distinct()
                .collect(Collectors.joining("；"));
    }

    private static void validateAnswer(Question question, ClarificationAnswer answer, List<FieldError> errors) {
        String path = "answers[" + question.id() + "]";
        List<String> selected = answer.selectedOptions() == null ? List.of()
                : answer.selectedOptions().stream().map(ClarificationRules::trim).toList();
        if (selected.size() > MAX_OPTIONS) {
            errors.add(new FieldError(path, "最多选择 " + MAX_OPTIONS + " 个选项"));
        }
        if (new HashSet<>(selected).size() != selected.size()) {
            errors.add(new FieldError(path, "选项不能重复"));
        }
        for (String option : selected) {
            String value = trim(option);
            if (value == null || value.isBlank()) {
                errors.add(new FieldError(path, "选项不能为空"));
            } else if (!question.options().contains(value)) {
                errors.add(new FieldError(path, "选项不在候选中：" + value));
            }
        }
        String customText = answer.customText() == null ? "" : answer.customText().trim();
        if (customText.length() > MAX_ANSWER_LENGTH) {
            errors.add(new FieldError(path, "补充内容不能超过 " + MAX_ANSWER_LENGTH + " 个字符"));
        }
        switch (question.type()) {
            case SINGLE_CHOICE -> {
                if (selected.size() > 1) {
                    errors.add(new FieldError(path, "只能选择一个选项"));
                } else if (selected.isEmpty() && customText.isBlank()) {
                    errors.add(new FieldError(path, "请选择一个选项或填写补充内容"));
                }
            }
            case MULTIPLE_CHOICE -> {
                if (selected.isEmpty() && customText.isBlank()) {
                    errors.add(new FieldError(path, "至少选择一项或填写补充内容"));
                }
            }
            case TEXT -> {
                if (!selected.isEmpty()) {
                    errors.add(new FieldError(path, "文本题不能选择选项"));
                }
                if (customText.isBlank()) {
                    errors.add(new FieldError(path, "请填写内容"));
                }
            }
        }
    }

    private static QuestionValidation invalid(String path, String message) {
        return new QuestionValidation(List.of(), List.of(new FieldError(path, message)));
    }

    private static List<FieldError> limit(List<FieldError> errors) {
        if (errors.size() <= MAX_ERRORS) {
            return List.copyOf(errors);
        }
        return List.copyOf(errors.subList(0, MAX_ERRORS));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static QuestionType parseType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return QuestionType.valueOf(value.trim());
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }
}