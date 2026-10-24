package com.qiheng.erp.ai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.domain.vo.ChatMessageVO;
import com.qiheng.erp.ai.domain.vo.VisibleMessageContent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** 新流水直接返回 type/data；旧正文仅映射允许展示的字段，不暴露整份原始 JSON。 */
@Service
@RequiredArgsConstructor
public class VisibleMessageCodec {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> TYPES = Set.of("TEXT", "CLARIFICATION", "PLAN", "NEEDS", "ERROR");
    private final ObjectMapper mapper;

    /** 序列化展示消息 */
    public String encode(VisibleMessageContent content) {
        try {
            return mapper.writeValueAsString(content);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("展示消息序列化失败", error);
        }
    }

    /** 反序列化展示消息 */
    public VisibleMessageContent decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return text("");
        }
        try {
            JsonNode root = mapper.readTree(raw);
            if (root == null || root.isNull()) {
                return text("");
            }
            String type = root.path("type").asText();
            if (TYPES.contains(type) && root.path("data").isObject()) {
                Set<String> fields = switch (type) {
                    case "TEXT" -> Set.of("text", "replyToMessageId");
                    case "CLARIFICATION" -> Set.of("text", "plan", "questions", "threadId", "pauseId");
                    case "PLAN" -> Set.of("text", "plan", "threadId");
                    // NEEDS：需求数组定稿后的展示载荷
                    case "NEEDS" -> Set.of("text", "needs", "threadId");
                    // ERROR：status 标记区分未完成提示与运行失败提示
                    default -> Set.of("text", "threadId", "status");
                };
                Map<String, Object> data = new LinkedHashMap<>();
                for (String field : fields) {
                    if (root.path("data").has(field)) {
                        data.put(field, mapper.convertValue(root.path("data").get(field), Object.class));
                    }
                }
                return new VisibleMessageContent(type, data);
            }
            String text = root.path("text").asText("");
            JsonNode plan = root.path("metadata").path("plan");
            if (plan.isObject() && !plan.isEmpty()) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("text", text);
                data.put("plan", mapper.convertValue(plan, new TypeReference<Map<String, Object>>() { }));
                if (plan.path("needs_clarification").asBoolean(false)) {
                    data.put("questions", mapper.convertValue(plan.path("clarification_questions"), Object.class));
                    return new VisibleMessageContent("CLARIFICATION", data);
                }
                return new VisibleMessageContent("PLAN", data);
            }
            return text(root.isTextual() ? root.asText() : text);
        } catch (JsonProcessingException error) {
            // 无法解释的旧 JSON 不暴露原始对象；历史纯文本仍可正常显示。
            String trimmed = raw.stripLeading();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                return text("该历史消息暂时无法展示。");
            }
            return text(raw);
        }
    }

    /** 纯文本消息 */
    public VisibleMessageContent text(String text) {
        return new VisibleMessageContent("TEXT", Map.of("text", text));
    }

    /** 转换为视图模型 */
    public ChatMessageVO toView(AiMessage message) {
        return new ChatMessageVO(string(message.getId()), message.getRequestId() == null ? "" : message.getRequestId(),
                string(message.getSeq()),
                message.getRole(), message.getKind(), decode(message.getContent()),
                message.getCreateTime() == null ? null : message.getCreateTime().format(TIME));
    }

    /** 转换为字符串 */
    private String string(Long value) {
        return value == null ? null : value.toString();
    }
}
