package com.qiheng.erp.ai.service.impl;

import com.qiheng.erp.ai.constant.ConversationDefaults;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.domain.vo.AssistantOverviewVO;
import com.qiheng.erp.ai.domain.vo.ChatMessageVO;
import com.qiheng.erp.ai.domain.vo.ConversationDetailVO;
import com.qiheng.erp.ai.domain.vo.ConversationMessagePageVO;
import com.qiheng.erp.ai.domain.vo.ConversationSummaryVO;
import com.qiheng.erp.ai.domain.vo.RunStateVO;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.service.AiAssistantService;
import com.qiheng.erp.ai.service.VisibleMessageCodec;
import com.qiheng.erp.ai.graph.support.ConversationExecutionLock;
import com.qiheng.erp.ai.service.GraphService;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.redisson.api.RLock;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * AI 助手业务实现。
 *
 * @author Li
 * @since 2026-10-15
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistantServiceImpl implements AiAssistantService {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;
    private final GraphService graphService;
    private final VisibleMessageCodec messageCodec;
    private final ConversationExecutionLock executionLock;
    private final TransactionTemplate transactions;

    /**
     *  会话列表，按最近一次活动倒序。
     */
    @Override
    public AssistantOverviewVO overview(Long userId) {
        List<AiConversation> rows = conversationMapper.selectOverviewByUser(userId);
        List<ConversationSummaryVO> summaries = rows.stream()
                .map(this::toSummary)
                .toList();
        return new AssistantOverviewVO(currentTime(), Collections.emptyList(), summaries);
    }

    /**
     *  创建会话。
     */
    @Override
    public ConversationDetailVO create(Long userId) {
        AiConversation conversation = new AiConversation()
                .setUserId(userId)
                .setTitle(ConversationDefaults.TITLE)
                .setDescription(ConversationDefaults.DESCRIPTION);
        transactions.executeWithoutResult(status -> conversationMapper.insert(conversation));
        return toDetail(conversation);
    }

    /**
     *  会话详情。
     */
    @Override
    public ConversationDetailVO detail(Long conversationId, Long userId) {
        AiConversation conversation = conversationMapper.selectOwned(conversationId, userId);
        if (conversation == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        return toDetail(conversation);
    }

    @Override
    public void rename(Long conversationId, Long userId, String title) {
        AiConversation conversation = conversationMapper.selectOwned(conversationId, userId);
        if (conversation == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        transactions.executeWithoutResult(status -> conversationMapper.updateTitle(conversationId, title));
    }

    @Override
    public void delete(Long conversationId, Long userId) {
        if (conversationMapper.selectOwned(conversationId, userId) == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        RLock lock = executionLock.of(conversationId);
        if (!lock.tryLock()) {
            throw new BizException("会话正在执行，请结束后再删除");
        }
        try {
            // 事务提交完成后才释放锁，执行入口不会读到尚未提交的删除状态。
            transactions.executeWithoutResult(status -> {
                if (conversationMapper.selectOwned(conversationId, userId) == null) {
                    throw new BizException(ErrorCode.DATA_NOT_FOUND);
                }
                messageMapper.softDeleteByConversation(conversationId, userId);
                conversationMapper.softDelete(conversationId);
            });
        } finally {
            lock.unlock();
        }
    }

    @Override
    public ConversationMessagePageVO pageMessages(Long conversationId, Long userId, int pageNum, int pageSize) {
        AiConversation conversation = conversationMapper.selectOwned(conversationId, userId);
        if (conversation == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND);
        }
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), 100);
        int offset = (safePageNum - 1) * safePageSize;
        long total = messageMapper.countVisibleMessages(conversationId, userId);
        List<AiMessage> rows = total == 0 ? List.of() : messageMapper.selectVisibleMessages(conversationId, userId, offset, safePageSize);
        List<ChatMessageVO> records = rows.stream().map(messageCodec::toView).toList();
        return new ConversationMessagePageVO(records, total, safePageNum, safePageSize);
    }

    private ConversationSummaryVO toSummary(AiConversation conversation) {
        String updatedAt = conversation.getLastMessageAt() == null
                ? format(conversation.getUpdateTime())
                : format(conversation.getLastMessageAt());
        return new ConversationSummaryVO(
                String.valueOf(conversation.getId()),
                conversation.getTitle(),
                conversation.getDescription(),
                updatedAt
        );
    }

    /**
     *  转换为会话详情。
     */
    private ConversationDetailVO toDetail(AiConversation conversation) {
        RunStateVO runState = graphService.runState(conversation.getId(), conversation.getUserId());
        return new ConversationDetailVO(
                String.valueOf(conversation.getId()),
                conversation.getTitle(),
                conversation.getDescription(),
                format(conversation.getLastMessageAt() == null ? conversation.getUpdateTime() : conversation.getLastMessageAt()),
                runState.running(),
                runState.node(),
                runState.label(),
                runState.status()
        );
    }

    private String currentTime() {
        return format(LocalDateTime.now());
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(TIME_FORMATTER);
    }
}
