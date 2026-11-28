package com.qiheng.erp.ai.experience;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.qiheng.erp.ai.domain.entity.AiExperience;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper.ExperienceTask;
import com.qiheng.erp.ai.mapper.AiExperienceMapper;
import com.qiheng.erp.ai.mapper.AiExperienceTaskMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

/** 任务登记和提取结果的短事务。 */
@Service
public class ExperienceTaskService {
    private static final Logger log = LoggerFactory.getLogger(ExperienceTaskService.class);
    private final AiExperienceTaskMapper tasks;
    private final AiAgentTraceMapper traces;
    private final AiExperienceMapper experiences;
    private final AiMessageMapper messages;
    private final ObjectMapper mapper;

    public ExperienceTaskService(AiExperienceTaskMapper tasks, AiAgentTraceMapper traces,
                                 AiExperienceMapper experiences, AiMessageMapper messages, ObjectMapper mapper) {
        this.tasks = tasks;
        this.traces = traces;
        this.experiences = experiences;
        this.messages = messages;
        this.mapper = mapper;
    }

    /** 原始 FINAL 含义不变，额外终态流水和任务登记必须一起提交。 */
    @Transactional
    public void registerTerminal(long userId, long conversationId, String agentType, String taskId,
                                 String eventType, String text) {
        if (!hasCompleteTrace(traces.selectEvents(userId, conversationId, taskId, agentType), eventType)) {
            log.warn("经验任务流水不完整，跳过登记 agentType={} taskId={}", agentType, taskId);
            return;
        }
        if (tasks.register(userId, conversationId, agentType, taskId) == 0) {
            return;
        }
        try {
            traces.insert(userId, conversationId, taskId, agentType, eventType,
                    mapper.writeValueAsString(Map.of("text", text == null ? "" : text)));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("经验终态流水序列化失败", error);
        }
    }

    /** 只检查能够从已存流水证实的缺失；写入阶段未留下痕迹的缺失无法在这里发现。 */
    private boolean hasCompleteTrace(List<AiAgentTraceMapper.TraceEvent> events, String eventType) {
        if (events == null || events.isEmpty()) {
            return false;
        }
        boolean input = false;
        boolean finalReply = false;
        boolean modelFailure = false;
        boolean errorEvent = false;
        Map<String, String> calls = new HashMap<>();
        Set<String> responses = new HashSet<>();
        try {
            for (var event : events) {
                JsonNode content = mapper.readTree(event.content());
                if (content == null || !content.isObject()) {
                    return false;
                }
                switch (event.eventType()) {
                    case "INPUT" -> input |= !content.path("text").asText("").isBlank();
                    case "FINAL" -> finalReply |= !content.path("text").asText("").isBlank();
                    case "MODEL_FAILURE" -> modelFailure |= !content.path("text").asText("").isBlank();
                    case "ERROR", "VALIDATION_ERROR" -> errorEvent = true;
                    case "TOOL_CALL", "TOOL_RESULT" -> {
                        String callId = content.path("callId").asText("");
                        String name = content.path("name").asText("");
                        if (callId.isBlank() || name.isBlank()) {
                            return false;
                        }
                        if ("TOOL_CALL".equals(event.eventType())) {
                            calls.put(callId, name);
                        } else {
                            responses.add(callId + "\u0000" + name);
                        }
                    }
                    default -> { }
                }
            }
        } catch (JsonProcessingException | IllegalArgumentException error) {
            return false;
        }
        Set<String> expected = new HashSet<>();
        calls.forEach((id, name) -> expected.add(id + "\u0000" + name));
        boolean terminal = "TASK_COMPLETED".equals(eventType) ? finalReply
                : "TASK_FAILED".equals(eventType) && (modelFailure || finalReply && errorEvent);
        return input && terminal && expected.equals(responses);
    }

    public List<ExperienceTask> pending() {
        return tasks.pending();
    }

    public boolean claim(ExperienceTask task) {
        return tasks.claim(task.userId(), task.conversationId(), task.agentType(), task.taskId()) == 1;
    }

    /** 流水为空时区分已删除会话和暂时缺失的流水，避免删除后的任务永久重试。 */
    @Transactional
    public boolean completeIfDeleted(ExperienceTask task) {
        if (messages.lockOwnedConversation(task.conversationId(), task.userId()) != null) {
            return false;
        }
        if (tasks.finish(task.userId(), task.conversationId(), task.agentType(), task.taskId(), "COMPLETED", 0) != 1) {
            throw new IllegalStateException("经验任务已不在处理中");
        }
        return true;
    }

    /** 已删除会话不再提取；会话行锁与删除操作的行更新串行。 */
    @Transactional
    public void complete(ExperienceTask task, List<String> texts) {
        boolean exists = messages.lockOwnedConversation(task.conversationId(), task.userId()) != null;
        if (tasks.finish(task.userId(), task.conversationId(), task.agentType(), task.taskId(), "COMPLETED", 0) != 1) {
            throw new IllegalStateException("经验任务已不在处理中");
        }
        if (!exists) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<AiExperience> records = texts.stream().map(text -> new AiExperience().setId(IdWorker.getId())
                    .setAgentType(task.agentType()).setExperienceText(text)
                    .setSourceTaskId(task.taskId()).setStatus("PUBLISHED").setEffectiveFrom(now)
                    .setCreateTime(now).setUpdateTime(now).setDeleted(0)).toList();
        if (!records.isEmpty() && experiences.insertPublishedBatch(records) != records.size()) {
            throw new IllegalStateException("经验批量写入数量不一致");
        }
    }

    public void release(ExperienceTask task) {
        tasks.release(task.userId(), task.conversationId(), task.agentType(), task.taskId());
    }

    public void releaseAfterOverflow(ExperienceTask task, int attempts) {
        tasks.finish(task.userId(), task.conversationId(), task.agentType(), task.taskId(), "PENDING", attempts);
    }

    public void markError(ExperienceTask task, int attempts) {
        tasks.finish(task.userId(), task.conversationId(), task.agentType(), task.taskId(), "ERROR", attempts);
    }

    /** 仅在持有跨实例定时任务锁后调用，避免回收仍在执行的任务。 */
    public void recoverProcessing() {
        tasks.recoverProcessing();
    }
}
