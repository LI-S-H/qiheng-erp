package com.qiheng.erp.ai.memory.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.NodeCode;
import com.qiheng.erp.ai.domain.entity.AiNodeWorkingMemory;
import com.qiheng.erp.ai.memory.summary.ContextCompressor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.qiheng.erp.ai.constant.GraphStateKeys.CONVERSATION_ID;
import static com.qiheng.erp.ai.constant.GraphStateKeys.USER_ID;

/**
 * Planner 节点的历史上下文：把工作记忆里的纯文本转写还原成模型看得见的消息。
 *
 * <p>工作记忆一行 = 模型的一条消息，行首标记决定角色，正文原样带出去。
 * 读侧不做任何配对、反查或协议判定：工作记忆里是什么，模型就看到什么。</p>
 *
 * <p>当前需求、澄清问答和最近格式错误由 State 显式传入，不从记忆重复注入。</p>
 *
 * @author Li
 * @since 2026-10-15
 */
@Slf4j
public final class PlannerMemoryContext {

    private final NodeMemoryStore store;

    private final ContextCompressor compressor;

    private final ObjectMapper mapper;

    public PlannerMemoryContext(NodeMemoryStore store, ContextCompressor compressor, ObjectMapper mapper) {
        this.store = store;
        this.compressor = compressor;
        this.mapper = mapper;
    }

    /**
     * 读取历史消息。
     *
     * @param state 当前图状态，取会话与用户归属
     * @return 交给模型的历史消息；记忆不可用时返回空列表
     */
    public List<Message> read(OverAllState state) {
        return read(state, null);
    }

    /**
     * 读取历史消息，并在触发历史压缩时回调。
     *
     * <p>回调只在真正要压缩的那一刻触发：{@code true} 表示开始压缩、{@code false} 表示结束
     * （成功与失败都会回调）。压缩发生在读取路径里，调用方据此推送进度事件，
     * 用户才不会在摘要模型调用期间看到界面无响应。</p>
     *
     * @param state        当前图状态
     * @param onCompacting 压缩状态回调；为 {@code null} 时不通知
     * @return 交给模型的历史消息；记忆不可用时返回空列表
     */
    public List<Message> read(OverAllState state, Consumer<Boolean> onCompacting) {
        Object conversation = state.value(CONVERSATION_ID).orElse(null);
        Object user = state.value(USER_ID).orElse(null);
        if (conversation == null || user == null) {
            return List.of();
        }

        try {
            long conversationId = Long.parseLong(conversation.toString());
            long userId = Long.parseLong(user.toString());
            List<AiNodeWorkingMemory> window = store.read(conversationId, userId, NodeCode.PLANNER);
            if (window == null || window.isEmpty()) {
                return List.of();
            }
            return buildContext(conversationId, userId, window, onCompacting);
        } catch (RuntimeException error) {
            // 记忆服务不可用时仍可依据当前完整 State 完成本次规划。
            log.warn("Planner 历史上下文读取失败，使用当前任务 State", error);
            return List.of();
        }
    }

    /**
     * 把工作记忆窗口转成历史消息；触发压缩时把压缩结果原样写回窗口。
     */
    private List<Message> buildContext(long conversationId, long userId, List<AiNodeWorkingMemory> window,
                                       Consumer<Boolean> onCompacting) {
        IdentityHashMap<Message, AiNodeWorkingMemory> sources = new IdentityHashMap<>();
        List<Message> messages = new ArrayList<>(window.size());
        for (AiNodeWorkingMemory row : window) {
            Message message = WorkingMemoryTranscript.toMessage(row.getContent());
            if (message == null) {
                // 非本协议的行（旧的事件 JSON 行）不进入模型视野，保留在库里供排查。
                log.warn("跳过非本协议的工作记忆行 id={}", row.getId());
                continue;
            }
            messages.add(message);
            sources.put(message, row);
        }

        List<Message> compacted = messages;
        if (compressor.needsCompaction(messages)) {
            // 压缩是同步阻塞的摘要调用：先推开始，再压；失败也要收尾，界面才不会永远停在"正在压缩"
            notifyCompacting(onCompacting, true);
            try {
                compacted = compressor.compact(messages);
            } finally {
                notifyCompacting(onCompacting, false);
            }
        }

        if (compacted == messages) {
            return messages;
        }

        // 压缩器留下的新消息就是摘要：转成带摘要标记的助手消息，模型才能认出这是摘要。
        List<Message> normalized = new ArrayList<>(compacted.size());
        for (Message message : compacted) {
            normalized.add(sources.containsKey(message)
                    ? message
                    : WorkingMemoryTranscript.summaryMessage(message.getText()));
        }

        try {
            store.replaceWorkingMemory(conversationId, userId, NodeCode.PLANNER, window,
                    replacements(normalized, messages, sources));
        } catch (RuntimeException | JsonProcessingException error) {
            // 压缩窗口保存失败时事务会回滚，当前调用也继续使用原始上下文。
            log.warn("Planner 工作记忆压缩保存失败，保留原窗口", error);
            return messages;
        }
        return normalized;
    }

    /**
     * 把压缩后的消息翻译回工作记忆行：正文原样存回，标记本来就在正文里。
     *
     * <p>来源引用：保留消息指向它原来的流水行，被摘要掉的消息合并进摘要行。</p>
     */
    private List<AiNodeWorkingMemory> replacements(List<Message> compacted, List<Message> original,
                                                    IdentityHashMap<Message, AiNodeWorkingMemory> sources)
            throws JsonProcessingException {
        Set<Long> summarizedRefs = new LinkedHashSet<>();
        Set<Message> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        retained.addAll(compacted);
        for (Message message : original) {
            if (!retained.contains(message)) {
                summarizedRefs.addAll(sourceRefs(sources.get(message)));
            }
        }

        List<AiNodeWorkingMemory> result = new ArrayList<>();
        for (Message message : compacted) {
            AiNodeWorkingMemory source = sources.get(message);
            result.add(new AiNodeWorkingMemory()
                    .setContent(message.getText() == null ? "" : message.getText())
                    .setSourceMemoryIds(mapper.writeValueAsString(
                            source == null ? summarizedRefs : sourceRefs(source))));
        }
        return result;
    }

    /** 通知压缩状态；回调缺省时静默跳过。 */
    private static void notifyCompacting(Consumer<Boolean> listener, boolean compacting) {
        if (listener != null) {
            listener.accept(compacting);
        }
    }

    private List<Long> sourceRefs(AiNodeWorkingMemory row) {
        if (row == null) {
            return List.of();
        }
        try {
            if (row.getSourceMemoryIds() != null) {
                return mapper.readValue(row.getSourceMemoryIds(), new TypeReference<>() { });
            }
        } catch (JsonProcessingException error) {
            log.warn("工作记忆来源引用无法解析 memoryId={}", row.getId());
        }
        return List.of();
    }
}
