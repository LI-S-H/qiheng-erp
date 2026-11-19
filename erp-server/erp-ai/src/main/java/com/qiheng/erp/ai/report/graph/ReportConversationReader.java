package com.qiheng.erp.ai.report.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.mapper.AiMessageMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 读取本轮用户原话和追问回答，整理为报告可阅读的文本。 */
public final class ReportConversationReader {
    private final AiMessageMapper messages;
    private final ObjectMapper mapper;

    public ReportConversationReader(AiMessageMapper messages, ObjectMapper mapper) {
        this.messages = messages;
        this.mapper = mapper;
    }

    /** 仅按可信身份和运行标识查询，不从模型输入取得身份。 */
    public String read(AgentToolContext identity, String runId) throws IOException {
        var rows = messages.selectRunMessages(identity.userId(), identity.conversationId(), runId);
        String userInput = null;
        StringBuilder answers = new StringBuilder();
        Map<String, JsonNode> calls = new LinkedHashMap<>();
        for (var row : rows) {
            JsonNode content = mapper.readTree(row.getContent());
            if ("USER_INPUT".equals(row.getKind())) {
                if (userInput != null || !content.isTextual()) {
                    throw new IllegalStateException("本轮用户输入记录无效");
                }
                userInput = content.asText();
            } else if ("TOOL_CALL".equals(row.getKind()) && "ask_user".equals(content.path("name").asText())) {
                calls.put(content.path("callId").asText(), parseValue(content.path("arguments")));
            } else if ("TOOL_RESULT".equals(row.getKind()) && "ask_user".equals(content.path("name").asText())) {
                JsonNode call = calls.remove(content.path("callId").asText());
                if (call == null) {
                    throw new IllegalStateException("追问回答缺少对应问题");
                }
                appendAnswers(answers, call, parseValue(content.path("result")));
            }
        }
        if (userInput == null || userInput.isBlank()) {
            throw new IllegalStateException("未找到本轮用户输入");
        }
        return "用户原始需求：\n" + userInput + "\n\n补充信息：\n"
                + (answers.isEmpty() ? "无" : answers.toString().stripTrailing());
    }

    /** 工具参数和结果在消息 JSON 中以字符串保存，需再解析一层。 */
    private JsonNode parseValue(JsonNode node) throws IOException {
        if (node.isTextual()) {
            return mapper.readTree(node.asText());
        }
        return node;
    }

    /** 根据问题 ID 配对答案，保留原问题和实际回答，不猜测业务含义。 */
    private void appendAnswers(StringBuilder text, JsonNode call, JsonNode result) {
        JsonNode questions = call.path("questions");
        JsonNode answers = result.path("answers");
        if (!questions.isArray() || !answers.isArray()) {
            throw new IllegalStateException("追问内容格式无效");
        }
        for (JsonNode question : questions) {
            JsonNode matched = null;
            for (JsonNode answer : answers) {
                if (question.path("id").asText().equals(answer.path("questionId").asText())) {
                    matched = answer;
                    break;
                }
            }
            if (matched == null || question.path("question").asText().isBlank()) {
                throw new IllegalStateException("追问与回答不匹配");
            }
            text.append("问题：").append(question.path("question").asText()).append('\n');
            text.append("回答：");
            boolean hasAnswer = false;
            for (JsonNode selected : matched.path("selectedOptions")) {
                if (hasAnswer) {
                    text.append("、");
                }
                text.append(selected.asText());
                hasAnswer = true;
            }
            String custom = matched.path("customText").asText("");
            if (!custom.isBlank()) {
                if (hasAnswer) {
                    text.append("；");
                }
                text.append(custom);
                hasAnswer = true;
            }
            if (!hasAnswer) {
                throw new IllegalStateException("追问回答为空");
            }
            text.append('\n');
        }
        String note = result.path("note").asText("");
        if (!note.isBlank()) {
            text.append("其他补充：").append(note).append('\n');
        }
    }
}
