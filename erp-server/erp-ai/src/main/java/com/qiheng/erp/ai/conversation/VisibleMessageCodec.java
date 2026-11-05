package com.qiheng.erp.ai.conversation;

import com.fasterxml.jackson.core.JsonProcessingException;
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

/** 按消息类型转换当前会话流水，不暴露其他工具的内部信息。 */
@Service
@RequiredArgsConstructor
public class VisibleMessageCodec {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
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
        return decode(raw, null);
    }

    /** 读取当前消息及已保存的普通文本、追问工具消息。 */
    private VisibleMessageContent decode(String raw, String kind) {
        if (raw == null || raw.isBlank()) {
            return text("");
        }
        try {
            JsonNode root = mapper.readTree(raw);
            if (root == null || root.isNull()) {
                return text("");
            }
            // 早期流水采用 type/data 包装，保留普通文本和 ask_user 历史。
            if (root.isObject() && root.path("data").isObject()) {
                String type = root.path("type").asText();
                if ("TEXT".equals(type)) return text(root.path("data").path("text").asText(""));
                if (("TOOL_CALL".equals(type) || "TOOL_RESULT".equals(type)) && type.equals(kind)) {
                    root = root.path("data");
                }
            }
            if (("TOOL_CALL".equals(kind) || "TOOL_RESULT".equals(kind)) && root.isObject()) {
                if (!"ask_user".equals(root.path("name").asText())) return text("");
                Set<String> fields = "TOOL_CALL".equals(kind)
                        ? Set.of("callId", "name", "arguments") : Set.of("callId", "name", "result");
                Map<String, Object> data = new LinkedHashMap<>();
                for (String field : fields) {
                    if (root.has(field)) data.put(field, mapper.convertValue(root.get(field), Object.class));
                }
                return new VisibleMessageContent(kind, data);
            }
            if (root.isObject() && !root.has("type") && root.path("text").isTextual()) {
                return text(root.path("text").asText());
            }
            return text(root.isTextual() ? root.asText() : "该历史消息暂时无法展示。");
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
                message.getRole(), message.getKind(), decode(message.getContent(), message.getKind()),
                message.getCreateTime() == null ? null : message.getCreateTime().format(TIME));
    }

    /** 转换为字符串 */
    private String string(Long value) {
        return value == null ? null : value.toString();
    }
}
