package com.qiheng.erp.ai.trace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 记录单个智能体在一次任务中的执行流水。
 *
 * <p>流水只追加，是排障与后续经验提取的事实来源；写入失败只记录错误，不影响任务本身，
 * 因此流水可能不完整。</p>
 *
 * @author Li
 * @since 2026-11-13
 */
public final class AiAgentTraceRecorder {
    private static final Logger log = LoggerFactory.getLogger(AiAgentTraceRecorder.class);

    /** 一次流水所属的任务与智能体。 */
    public record Scope(long userId, long conversationId, String taskId, String agentType) {
    }

    private final AiAgentTraceMapper traces;
    private final ObjectMapper mapper;
    private final Scope scope;

    /**
     * 建立流水记录器。
     *
     * @param traces 流水写入通道
     * @param mapper JSON 序列化组件
     * @param scope 流水归属的任务与智能体
     */
    public AiAgentTraceRecorder(AiAgentTraceMapper traces, ObjectMapper mapper, Scope scope) {
        this.traces = traces;
        this.mapper = mapper;
        this.scope = scope;
    }

    /**
     * 记录一条结构化事件；内容直接序列化为 JSON。
     *
     * @param eventType 事件类型
     * @param content 事件内容
     */
    public void record(String eventType, Object content) {
        try {
            traces.insert(scope.userId(), scope.conversationId(), scope.taskId(), scope.agentType(),
                    eventType, mapper.writeValueAsString(content));
        } catch (JsonProcessingException | RuntimeException error) {
            log.error("智能体流水写入失败 taskId={} agentType={} eventType={} errorType={}",
                    scope.taskId(), scope.agentType(), eventType, error.getClass().getName());
        }
    }

    /**
     * 记录一段原文；原文写入内容的 text 字段。
     *
     * @param eventType 事件类型
     * @param text 事件原文
     */
    public void recordText(String eventType, String text) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("text", text == null ? "" : text);
        record(eventType, content);
    }
}
