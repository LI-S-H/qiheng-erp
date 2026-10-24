package com.qiheng.erp.ai.graph.support;

import com.qiheng.erp.ai.constant.MessageRunStatus;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.domain.vo.VisibleMessageContent;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.service.VisibleMessageCodec;
import com.qiheng.erp.common.exception.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 用户可见消息流水的唯一读写入口。
 *
 * <p>序号分配、展示内容编码、会话活跃校验和事务边界都收在本类；
 * 调用方不再直接接触消息 Mapper，表结构或写入规则变化只影响这里。</p>
 *
 * @author Li
 * @since 2026-10-16
 */
@Component
@RequiredArgsConstructor
public class ConversationMessageStore {

    private final AiMessageMapper messageMapper;
    private final AiConversationMapper conversationMapper;
    private final VisibleMessageCodec messageCodec;
    private final TransactionTemplate transactions;

    /**
     * 追加一行用户可见消息并刷新会话活跃时间。调用方在会话锁内调用，本方法自带短事务。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param requestId      本轮请求标识
     * @param role           消息角色
     * @param kind           消息类型
     * @param runStatus      轮次状态，仅用户输入行需要
     * @param errorCode      错误码，成功时为 null
     * @param content        展示内容
     * @return 落库后的消息
     */
    public AiMessage append(long conversationId, long userId, String requestId, String role,
                            String kind, String runStatus, String errorCode, VisibleMessageContent content) {
        AiMessage message = new AiMessage();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRequestId(requestId);
        message.setSeq(messageMapper.selectVisibleMaxSeq(conversationId, userId) + 1);
        message.setRole(role);
        message.setKind(kind);
        message.setRunStatus(runStatus);
        message.setErrorCode(errorCode);
        message.setContent(messageCodec.encode(content));
        message.setCreateTime(LocalDateTime.now());
        return transactions.execute(status -> {
            messageMapper.insert(message);
            if (conversationMapper.touchLastMessageAt(conversationId, LocalDateTime.now()) != 1) {
                throw new BizException("会话已失效");
            }
            return message;
        });
    }

    /**
     * 一轮失败收尾：助手错误提示（缺失时补写）与用户输入行失败状态在同一事务内提交。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param requestId      本轮请求标识
     * @param role           助手消息角色
     * @param kind           助手消息类型
     * @param content        失败提示内容
     * @param errorCode      错误码
     */
    public void fail(long conversationId, long userId, String requestId, String role,
                     String kind, VisibleMessageContent content, String errorCode) {
        transactions.executeWithoutResult(status -> {
            if (findFinal(conversationId, userId, requestId) == null) {
                append(conversationId, userId, requestId, role, kind, null, null, content);
            }
            finishRequest(conversationId, userId, requestId, MessageRunStatus.FAILED, errorCode);
        });
    }

    /**
     * 查询本轮已落库的助手终态消息。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param requestId      本轮请求标识
     * @return 已保存的终态消息，不存在时为 null
     */
    public AiMessage findFinal(long conversationId, long userId, String requestId) {
        return messageMapper.selectFinalByRequest(conversationId, userId, requestId);
    }

    /**
     * 查询最近一次用户输入，用于判定当前轮次。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @return 最近一次输入消息，不存在时为 null
     */
    public AiMessage findLatestInput(long conversationId, long userId) {
        return messageMapper.selectLatestInput(conversationId, userId);
    }

    /**
     * 按暂停标识查找问题卡片；旧消息未保存暂停标识时不猜测关联。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param pauseId        暂停标识
     * @return 对应的问题消息，不存在时为 null
     */
    public AiMessage findClarification(long conversationId, long userId, String pauseId) {
        return messageMapper.selectClarification(conversationId, userId, pauseId);
    }

    /**
     * 更新轮次终态；只允许 RUNNING 的用户输入行流转。
     *
     * @param conversationId 会话 ID
     * @param userId         归属用户
     * @param requestId      本轮请求标识
     * @param status         终态值
     * @param errorCode      错误码，成功时为 null
     */
    public void finishRequest(long conversationId, long userId, String requestId, String status, String errorCode) {
        messageMapper.finishRequest(conversationId, userId, requestId, status, errorCode);
    }
}
