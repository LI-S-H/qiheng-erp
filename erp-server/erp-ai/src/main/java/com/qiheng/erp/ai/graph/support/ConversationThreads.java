package com.qiheng.erp.ai.graph.support;

import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import org.springframework.stereotype.Component;

/**
 * 图检查点线程标识的唯一编解码入口。
 *
 * <p>线程格式 {@code erp:ai:graph:v1:{userId}:{conversationId}} 只在本类拼接与解析；
 * 归属校验（前缀必须属于当前用户）也收在这里，防止跨用户恢复。</p>
 *
 * @author Li
 * @since 2026-10-16
 */
@Component
public class ConversationThreads {

    private static final String THREAD_PREFIX = "erp:ai:graph:v1:";

    /**
     * 生成会话的检查点线程标识。
     *
     * @param userId         归属用户
     * @param conversationId 会话 ID
     * @return 线程标识
     */
    public String of(Long userId, Long conversationId) {
        return THREAD_PREFIX + userId + ":" + conversationId;
    }

    /**
     * 解析并校验线程标识，返回其中携带的会话 ID。
     *
     * @param threadId 待解析线程标识
     * @param userId   当前登录用户
     * @return 会话 ID
     * @throws BizException 线程不属于当前用户或格式非法
     */
    public long parse(String threadId, long userId) {
        if (!isOwned(threadId, userId)) {
            throw new BizException(ErrorCode.FORBIDDEN);
        }
        try {
            return Long.parseLong(threadId.substring(THREAD_PREFIX.length() + String.valueOf(userId).length() + 1));
        } catch (NumberFormatException error) {
            throw new BizException("任务标识无效");
        }
    }

    /**
     * 判断线程标识是否属于当前用户。
     *
     * @param threadId 待校验线程标识
     * @param userId   当前登录用户
     * @return 归属当前用户且格式合法时为 true
     */
    public boolean isOwned(String threadId, long userId) {
        return threadId != null && threadId.startsWith(prefix(userId)) && hasNumericConversationId(threadId, userId);
    }

    private String prefix(long userId) {
        return THREAD_PREFIX + userId + ":";
    }

    private boolean hasNumericConversationId(String threadId, long userId) {
        String tail = threadId.substring(prefix(userId).length());
        if (tail.isEmpty()) {
            return false;
        }
        for (int i = 0; i < tail.length(); i++) {
            if (!Character.isDigit(tail.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
