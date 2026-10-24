package com.qiheng.erp.ai.graph.runtime;

import com.qiheng.erp.ai.graph.support.ConversationMessageStore;
import com.qiheng.erp.ai.constant.AiMessageKind;
import com.qiheng.erp.ai.constant.MessageRunStatus;
import com.qiheng.erp.ai.domain.vo.VisibleMessageContent;
import com.qiheng.erp.ai.graph.runtime.RunContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 一轮运行的失败收尾：统一失败文案与失败落库，永不向调用方抛出。
 *
 * <p>失败落库自身再失败只记日志：异常处理路径不能因为持久化抖动而丢失原始失败原因。</p>
 *
 * @author Li
 * @since 2026-10-16
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RunFailureHandler {

    /** 对外失败文案；SSE run_stopped 事件与落库消息共用同一份。 */
    public static final String FAILED_MESSAGE_TEXT = "本轮任务执行失败，请稍后重试。";

    /** 失败错误码，落在用户输入行。 */
    public static final String FAILED_ERROR_CODE = "AI_RUN_FAILED";

    private static final String ROLE_ASSISTANT = "assistant";

    private final ConversationMessageStore messageStore;

    /**
     * 已有运行上下文时的失败收尾：补错误提示并把用户输入行标记为 FAILED。
     *
     * @param run   本轮运行上下文
     * @param error 触发失败的异常，仅用于日志
     */
    public void fail(RunContext run, RuntimeException error) {
        log.error("AI 图任务执行失败 conversationId={}", run.conversationId(), error);
        try {
            messageStore.fail(run.conversationId(), run.userId(), run.requestId(),
                    ROLE_ASSISTANT, AiMessageKind.ASSISTANT_FINAL,
                    new VisibleMessageContent("ERROR", Map.of("text", FAILED_MESSAGE_TEXT)), FAILED_ERROR_CODE);
        } catch (RuntimeException persistError) {
            log.warn("失败终态保存失败 conversationId={}", run.conversationId(), persistError);
        }
    }

    /**
     * 尚未产生运行上下文的失败收尾（准备阶段之前失败）：只结清可能存在的 RUNNING 行。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param requestId      本轮请求标识
     */
    public void markFailedSafely(long conversationId, long userId, String requestId) {
        try {
            messageStore.finishRequest(conversationId, userId, requestId, MessageRunStatus.FAILED, FAILED_ERROR_CODE);
        } catch (RuntimeException error) {
            log.warn("准备阶段失败终态保存失败 conversationId={}", conversationId, error);
        }
    }
}
