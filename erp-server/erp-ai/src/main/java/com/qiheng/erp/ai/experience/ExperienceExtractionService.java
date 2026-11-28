package com.qiheng.erp.ai.experience;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.agent.context.CompressionTranscript;
import com.qiheng.erp.ai.config.AiModelEnabledCondition;
import com.qiheng.erp.ai.config.properties.AiExperienceProperties;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper.ExperienceTask;
import com.qiheng.erp.ai.mapper.AiAgentTraceMapper.TraceEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * 从已完成任务流水提炼经验。
 *
 * <p>任务单位：MAIN 按一次请求的 runId，ANALYSIS 按一次分析任务的 taskId。
 * 流水先转成消息序列，超过预算时复用上下文压缩的降级策略；
 * 模型输出经过长度、脱敏和批内去重后写入经验表，agent_type 由任务本身决定。</p>
 *
 * @author Li
 * @since 2026-11-20
 */
@Component
@Conditional(AiModelEnabledCondition.class)
public final class ExperienceExtractionService {

    private static final Logger log = LoggerFactory.getLogger(ExperienceExtractionService.class);

    /** 关键错误索引最多列出的条数。 */
    private static final int MAX_ERROR_INDEX = 10;

    /** 索引里单条错误的最大字符数。 */
    private static final int ERROR_SNIPPET_CHARS = 80;

    /** 检索工具名称；查经验的行为不参与提炼，避免自我循环。 */
    private static final String SEARCH_TOOL = "searchExperience";

    /** 需要脱敏的密钥模式。 */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(sk-[A-Za-z0-9_-]{8,}|bearer\\s+[A-Za-z0-9._-]{8,}"
                    + "|(?:password|passwd|pwd|token|secret|api[-_]?key)\\s*[=:]\\s*[^\\s,;\"'}]+)");

    private final AiAgentTraceMapper traces;
    private final ExperienceTaskService tasks;
    private final ExperienceModelClient client;
    private final AiExperienceProperties properties;
    private final ObjectMapper mapper;
    private final String prompt;

    /**
     * 加载提炼提示词。
     *
     * @param traces 流水查询
     * @param tasks 任务领取与事务写入
     * @param client 模型客户端
     * @param properties 经验配置
     * @param mapper JSON 组件
     * @throws IOException 提示词读取失败
     */
    public ExperienceExtractionService(AiAgentTraceMapper traces, ExperienceTaskService tasks,
                                       ExperienceModelClient client, AiExperienceProperties properties,
                                       ObjectMapper mapper) throws IOException {
        this.traces = traces;
        this.tasks = tasks;
        this.client = client;
        this.properties = properties;
        this.mapper = mapper;
        this.prompt = new ClassPathResource("prompts/experience-extract.md")
                .getContentAsString(StandardCharsets.UTF_8);
    }

    /** 提取所有待处理任务的经验，单任务失败不阻断后续任务。 */
    public void extractPending() {
        if (!properties.enabled()) {
            return;
        }
        tasks.recoverProcessing();
        List<ExperienceTask> pending = tasks.pending();
        Deque<ExperienceTask> main = new ArrayDeque<>();
        Deque<ExperienceTask> analysis = new ArrayDeque<>();
        for (ExperienceTask task : pending) {
            if ("MAIN".equals(task.agentType())) {
                main.add(task);
            } else if ("ANALYSIS".equals(task.agentType())) {
                analysis.add(task);
            }
        }
        log.info("经验提取开始 main={} analysis={}", main.size(), analysis.size());
        try (var executor = Executors.newFixedThreadPool(Math.max(1, properties.extractionWorkerThreads()))) {
            Future<?> mainWork = executor.submit(() -> processQueue(main));
            Future<?> analysisWork = executor.submit(() -> processQueue(analysis));
            mainWork.get();
            analysisWork.get();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("经验提取被中断", error);
        } catch (ExecutionException error) {
            throw new IllegalStateException("经验提取工作线程失败", error.getCause());
        }
        log.info("经验提取本轮结束");
    }

    /** 失败任务退到队尾；本轮暂时不可恢复时保留 PENDING，避免一直空转。 */
    private void processQueue(Deque<ExperienceTask> queue) {
        Map<String, Integer> failures = new HashMap<>();
        while (!queue.isEmpty()) {
            ExperienceTask task = queue.remove();
            String key = task.userId() + ":" + task.conversationId() + ":" + task.agentType() + ":" + task.taskId();
            try {
                if (!tasks.claim(task)) {
                    failures.remove(key);
                    continue;
                }
                extractTask(task);
                failures.remove(key);
            } catch (ExperienceModelClient.ExtractionOutputException error) {
                int count = task.attemptCount();
                if (count < 2) {
                    int next = count + 1;
                    tasks.releaseAfterOverflow(task, next);
                    queue.add(new ExperienceTask(task.agentType(), task.taskId(), task.userId(),
                            task.conversationId(), task.eventCount(), next));
                } else {
                    tasks.markError(task, count);
                    failures.remove(key);
                    log.warn("经验提取结果异常 agentType={} taskId={} reason={}", task.agentType(), task.taskId(), error.getMessage());
                }
            } catch (RuntimeException error) {
                log.warn("经验提取暂时失败 agentType={} taskId={} errorType={}",
                        task.agentType(), task.taskId(), error.getClass().getName());
                try {
                    tasks.release(task);
                } catch (RuntimeException releaseError) {
                    log.warn("经验任务恢复待处理失败 agentType={} taskId={} errorType={}",
                            task.agentType(), task.taskId(), releaseError.getClass().getName());
                    failures.remove(key);
                    continue;
                }
                int attempts = failures.merge(key, 1, Integer::sum);
                int maxBackoffs = Math.max(1, properties.transientMaxAttemptsPerRun());
                if (attempts <= maxBackoffs) {
                    try {
                        long delay = Math.max(0L, properties.transientRetryBackoffMillis())
                                * (1L << Math.min(attempts - 1, 10));
                        Thread.sleep(delay);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("经验提取退避被中断", interrupted);
                    }
                    if (attempts < maxBackoffs) {
                        queue.addFirst(task);
                    } else {
                        queue.addLast(task);
                    }
                } else {
                    failures.remove(key);
                }
            }
        }
    }

    /** 提取单个任务；状态与经验由任务服务原子提交。 */
    private void extractTask(ExperienceTask task) {
        List<TraceEvent> events = traces.selectEvents(task.userId(), task.conversationId(),
                task.taskId(), task.agentType());
        if (events == null || events.isEmpty()) {
            if (tasks.completeIfDeleted(task)) {
                return;
            }
            throw new IllegalStateException("任务流水不存在");
        }
        CompressionTranscript.Result transcript = CompressionTranscript.render(
                toMessages(events), properties.extractionInputMaxTokens(), 1000, 2000);
        if (transcript == null) {
            log.warn("经验提取流水无法放入预算 agentType={} taskId={}", task.agentType(), task.taskId());
            throw new IllegalStateException("经验提取流水超过预算");
        }
        String input = errorIndex(events) + transcript.text();
        List<String> texts = client.extract(prompt, input);
        if (texts == null) {
            throw new IllegalStateException("经验提取模型调用失败");
        }
        List<String> cleaned = clean(texts);
        tasks.complete(task, cleaned);
        log.info("经验提取成功 agentType={} taskId={} count={}",
                task.agentType(), task.taskId(), cleaned.size());
    }

    /** 把流水事件转成消息序列，保持工具调用与响应配对。 */
    private List<Message> toMessages(List<TraceEvent> events) {
        List<Message> messages = new ArrayList<>(events.size());
        for (TraceEvent event : events) {
            JsonNode content = parse(event.content());
            switch (event.eventType()) {
                case "INPUT" -> messages.add(new UserMessage(text(content)));
                case "FINAL", "MODEL_OUTPUT" -> messages.add(new AssistantMessage(text(content)));
                case "TOOL_CALL" -> {
                    String name = content.path("name").asText("");
                    if (SEARCH_TOOL.equals(name)) {
                        continue;
                    }
                    String callId = content.path("callId").asText("call-" + event.id());
                    String arguments = content.path("text").asText("");
                    messages.add(AssistantMessage.builder().content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    callId, "function", name, arguments))).build());
                }
                case "TOOL_RESULT" -> {
                    String name = content.path("name").asText("");
                    if (SEARCH_TOOL.equals(name)) {
                        continue;
                    }
                    String callId = content.path("callId").asText("call-" + event.id());
                    String data = content.path("text").asText("");
                    messages.add(ToolResponseMessage.builder()
                            .responses(List.of(new ToolResponseMessage.ToolResponse(
                                    callId, name, data))).build());
                }
                case "ERROR", "MODEL_FAILURE", "FORMAT_FEEDBACK", "VALIDATION_ERROR" ->
                        messages.add(new AssistantMessage("[" + event.eventType() + "] " + text(content)));
                default -> {
                    // 未知事件不进入提炼输入。
                }
            }
        }
        return messages;
    }

    /** 把错误类事件固定放在转录开头，降级时也不会丢失错误类型。 */
    private String errorIndex(List<TraceEvent> events) {
        StringBuilder index = new StringBuilder();
        int count = 0;
        for (TraceEvent event : events) {
            if (!"ERROR".equals(event.eventType()) && !"MODEL_FAILURE".equals(event.eventType())
                    && !"FORMAT_FEEDBACK".equals(event.eventType())
                    && !"VALIDATION_ERROR".equals(event.eventType())) {
                continue;
            }
            if (count >= MAX_ERROR_INDEX) {
                index.append("- 另有更多同类事件\n");
                break;
            }
            index.append("- [").append(event.eventType()).append("] ")
                    .append(truncate(text(parse(event.content())), ERROR_SNIPPET_CHARS)).append('\n');
            count++;
        }
        return count == 0 ? "" : "[关键事件索引]\n" + index + "\n";
    }

    /** 脱敏、长度校验和批内去重。 */
    private List<String> clean(List<String> texts) {
        Set<String> seen = new HashSet<>();
        List<String> result = new ArrayList<>();
        for (String text : texts) {
            String value = SECRET.matcher(text == null ? "" : text.strip()).replaceAll("[REDACTED]");
            if (value.isBlank()) {
                continue;
            }
            if (value.length() > properties.maxTextChars()) {
                log.warn("经验超过 {} 字，丢弃 length={}", properties.maxTextChars(), value.length());
                continue;
            }
            if (!seen.add(normalize(value))) {
                continue;
            }
            result.add(value);
        }
        return result;
    }

    /** 解析事件内容，失败时返回空对象。 */
    private JsonNode parse(String content) {
        try {
            return mapper.readTree(content == null ? "{}" : content);
        } catch (Exception error) {
            return mapper.createObjectNode();
        }
    }

    /** 取事件正文，缺失时返回空串。 */
    private String text(JsonNode content) {
        String value = content.path("text").asText("");
        return value.isBlank() ? content.toString() : value;
    }

    /** 去空白和标点后的小写文本，用于批内去重。 */
    private String normalize(String text) {
        return text.replaceAll("[\\s\\p{Punct}，。；：！？、（）【】《》]", "").toLowerCase();
    }

    /** 截断文本，避免索引过长。 */
    private String truncate(String text, int limit) {
        return text.length() <= limit ? text : text.substring(0, limit) + "…";
    }
}
