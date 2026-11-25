package com.qiheng.erp.ai.conversation;

import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.qiheng.erp.ai.constant.ConversationDefaults;

/** 按会话顺序保存完整消息，并记录对应轮次的状态。 */
@Service
public class ConversationMessageStore {
    private final AiMessageMapper messages;

    public ConversationMessageStore(AiMessageMapper messages) {
        this.messages = messages;
    }

    /** 在会话行锁下分配序号；重复消息返回原记录。 */
    @Transactional
    public AiMessage append(AiMessage message) {
        if (messages.lockOwnedConversation(message.getConversationId(), message.getUserId()) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        if (message.getMessageKey() == null || message.getMessageKey().isBlank()) {
            throw new IllegalArgumentException("消息幂等标识不能为空");
        }
        var existing = messages.selectByMessageKey(message.getConversationId(), message.getMessageKey());
        if (existing != null) {
            return existing;
        }
        message.setSeq(messages.maxSequence(message.getConversationId()) + 1);
        messages.insert(message);
        messages.touchActivity(message.getConversationId(), message.getUserId());
        return message;
    }

    /** 同一报告过程反复更新一行，保证分配序号和更新均处于会话锁内。 */
    @Transactional
    public void saveReportProcess(AiMessage message) {
        if (messages.lockOwnedConversation(message.getConversationId(), message.getUserId()) == null) {
            throw new IllegalArgumentException("会话不存在或无权访问");
        }
        var existing = messages.selectByMessageKey(message.getConversationId(), message.getMessageKey());
        if (existing == null) {
            message.setSeq(messages.maxSequence(message.getConversationId()) + 1);
            messages.insert(message);
            messages.touchActivity(message.getConversationId(), message.getUserId());
            return;
        }
        if (messages.updateReportProcess(existing.getId(), message.getConversationId(), message.getUserId(),
                message.getContent()) != 1) {
            throw new IllegalStateException("报告过程记录无法更新");
        }
    }

    /** 根据首条输入生成简短标题，不改变已手动命名的会话。 */
    public void nameFirstInput(long userId, long conversationId, String input) {
        String title = input.strip().replaceAll("\\s+", " ");
        int count = title.codePointCount(0, title.length());
        if (count > ConversationDefaults.AUTO_TITLE_LENGTH) {
            title = title.substring(0, title.offsetByCodePoints(0, ConversationDefaults.AUTO_TITLE_LENGTH)) + "…";
        }
        if (!title.isBlank()) {
            messages.nameFirstInput(userId, conversationId, title, ConversationDefaults.TITLE);
        }
    }

    /** 更新本轮用户输入的状态，不修改消息正文。 */
    @Transactional
    public void updateRunStatus(long userId, long conversationId, String requestId, String status, String errorCode) {
        if (messages.updateRunStatus(userId, conversationId, requestId, status, errorCode) != 1) {
            throw new IllegalStateException("运行记录不存在或不唯一");
        }
    }

    /** 查询最后一次用户输入留下的状态。 */
    public String latestRunStatus(long userId, long conversationId) {
        return messages.latestRunStatus(conversationId, userId);
    }

    /** 查询最后一次用户输入对应的运行标识。 */
    public String latestRunId(long userId, long conversationId) {
        return messages.latestRunId(conversationId, userId);
    }
}
