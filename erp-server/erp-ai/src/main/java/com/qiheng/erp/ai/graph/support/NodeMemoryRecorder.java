package com.qiheng.erp.ai.graph.support;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.constant.NodeCode;
import com.qiheng.erp.ai.domain.dto.needs.ClarificationQuestion;
import com.qiheng.erp.ai.memory.node.NodeMemoryStore;
import com.qiheng.erp.ai.memory.node.WorkingMemoryTranscript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 节点记忆写入协议的唯一构造点。
 *
 * <p>两张表分工：流水表 {@code ai_node_memory} 存结构化事件 JSON，只增不改，
 * 供审计与追溯；工作记忆表 {@code ai_node_working_memory} 存「模型视角的纯文本转写」，
 * 一行 = 模型的一条消息，是节点下一轮读取历史的唯一入口。</p>
 *
 * <p>只有模型真正收到与返回的内容才进工作记忆（planner 输入与模型输出）；
 * 澄清提问、澄清回答、定稿标记、格式反馈、审阅问题等过程事件只进流水表——
 * 它们在模型视角里已经包含在上一轮输出与下一轮输入中，再记一遍是重复。</p>
 *
 * <p><b>写入边界是硬约束</b>：工作记忆的写入与读取只属于 planner 节点。其他节点
 * （need_check / need_review / 恢复入口）调用本类时只能用事件方法；一旦让它们写进
 * 工作记忆，过程事件就会混进模型视角，跟 State 注入的内容重复，而且读侧无法区分。</p>
 *
 * <p>记忆是审计与压缩素材，任何序列化或落库失败都只记日志，不阻塞主流程。</p>
 *
 * @author Li
 * @since 2026-10-16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NodeMemoryRecorder {

    private final NodeMemoryStore nodeMemoryStore;
    private final ObjectMapper mapper;

    /**
     * 写入 planner 本轮看到的输入。
     *
     * <p>记的是"本轮真正喂给模型的输入"，不是用户原话：轮次、时间与本轮任务句
     * 一并记下，排查时能对着记忆复现每一轮模型看到了什么。system prompt 不记——
     * 它是静态规则，不是本轮输入。</p>
     *
     * <p>首轮是新需求，澄清轮是人工回答；由 {@code PlannerNode} 在节点内写入，
     * 调用点位于图恢复之后、模型调用之前，保证只记录节点真实消费过的输入。</p>
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param requestId      本轮请求标识
     * @param round          本轮任务内的 planner 调用序号
     * @param time           本轮调用时间
     * @param task           本轮任务句
     * @param markedInput    本轮输入正文，带输入标记
     */
    public void plannerInput(long conversationId, long userId, String requestId, int round,
                             String time, String task, String markedInput) {
        record(conversationId, userId,
                Map.of("eventType", "USER_INPUT", "requestId", requestId, "round", round,
                        "time", time, "task", task, "query", markedInput),
                WorkingMemoryTranscript.input(markedInput));
    }

    /**
     * 写入模型原始输出。读取端把这段原文直接作为历史消息注入，其余事件仅作审计素材。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param requestId      本轮请求标识
     * @param round          本轮任务内的 planner 调用序号
     * @param rawOutput      模型原始输出
     */
    public void modelOutput(long conversationId, long userId, String requestId, int round, String rawOutput) {
        record(conversationId, userId,
                Map.of("eventType", "MODEL_OUTPUT", "requestId", requestId, "round", round,
                        "raw_output", rawOutput),
                WorkingMemoryTranscript.output(rawOutput));
    }

    /**
     * 写入澄清提问条目：由 {@code NeedCheckNode} 在暂停拍生成问题后立即记录。
     *
     * <p>问题原文只在这里保存一份；模型视角里这段内容在它自己的输出中，工作记忆不必再记。</p>
     */
    public void clarificationAsked(long conversationId, long userId, String requestId, String pauseId,
                                    List<ClarificationQuestion> questions) {
        recordEvent(conversationId, userId, Map.of(
                "eventType", "CLARIFICATION_ASKED",
                "requestId", requestId,
                "pauseId", pauseId,
                "questions", questions));
    }

    /** 写入澄清回答条目：恢复真正生效后记录，回答原文同时进入 State 供 planner 拼进下一轮输入。 */
    public void clarificationAnswered(long conversationId, long userId, String requestId, String pauseId,
                                      String answer) {
        recordEvent(conversationId, userId, Map.of(
                "eventType", "CLARIFICATION_ANSWERED",
                "requestId", requestId,
                "pauseId", pauseId,
                "answer", answer));
    }

    /** 写入定稿的“通过”标记：需求正文由同轮模型输出原文承载，条目只留轮次结论文档。 */
    public void acceptedPlan(long conversationId, long userId, String requestId, String status) {
        recordEvent(conversationId, userId, Map.of(
                "eventType", "ACCEPTED_PLAN",
                "requestId", requestId,
                "status", status));
    }

    /** 写入需求数组定稿的“通过”标记：需求正文由同轮模型输出原文承载，条目只留轮次结论文档。 */
    public void acceptedNeeds(long conversationId, long userId, String requestId, String status) {
        recordEvent(conversationId, userId, Map.of(
                "eventType", "ACCEPTED_NEEDS",
                "requestId", requestId,
                "status", status));
    }

    /**
     * 写入业务完整性审阅提出的问题，下一轮 planner 读取后能避免犯同样的错。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param requestId      本轮请求标识
     * @param issues         问题清单
     */
    public void reviewIssues(long conversationId, long userId, String requestId,
                             List<Map<String, Object>> issues) {
        recordEvent(conversationId, userId, Map.of(
                "eventType", "REVIEW_ISSUES",
                "requestId", requestId,
                "source", "need_review",
                "issues", issues));
    }

    /** 写入格式重试耗尽的结局标记：本轮没有产物收场。 */
    public void formatExhausted(long conversationId, long userId, String requestId) {
        recordEvent(conversationId, userId, Map.of(
                "eventType", "FORMAT_RETRY_EXHAUSTED",
                "requestId", requestId,
                "source", "plan_check",
                "result", "EXHAUSTED"));
    }

    /** 写入格式校验失败的反馈，下一轮 Planner 读取后避免犯同样的错。 */
    public void formatFeedback(long conversationId, long userId, String requestId, Map<String, Object> failure) {
        recordEvent(conversationId, userId, Map.of(
                "eventType", "FORMAT_FEEDBACK",
                "requestId", requestId,
                "source", "plan_check",
                "result", "REJECTED",
                "error", failure));
    }

    /**
     * 写入一条模型视角的记忆：流水表留结构化事件，工作记忆表留纯文本转写。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param event          流水记忆内容，结构化事件
     * @param transcript     工作记忆内容，纯文本转写
     */
    private void record(long conversationId, long userId, Object event, String transcript) {
        try {
            nodeMemoryStore.appendTranscript(conversationId, userId, NodeCode.PLANNER,
                    mapper.writeValueAsString(event), transcript);
        } catch (JsonProcessingException exception) {
            log.warn("节点记忆序列化失败 conversationId={}", conversationId, exception);
        } catch (RuntimeException exception) {
            log.warn("节点记忆写入失败 conversationId={}", conversationId, exception);
        }
    }

    /**
     * 只写流水记忆：过程事件不进入模型视角，失败一律降级告警。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param event          流水记忆内容，结构化事件
     */
    private void recordEvent(long conversationId, long userId, Object event) {
        try {
            nodeMemoryStore.appendEvent(conversationId, userId, NodeCode.PLANNER,
                    mapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            log.warn("节点记忆序列化失败 conversationId={}", conversationId, exception);
        } catch (RuntimeException exception) {
            log.warn("节点记忆写入失败 conversationId={}", conversationId, exception);
        }
    }
}
