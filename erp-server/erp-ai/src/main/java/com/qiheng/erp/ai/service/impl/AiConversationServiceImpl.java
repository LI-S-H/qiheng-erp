package com.qiheng.erp.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.qiheng.erp.ai.domain.dto.AiConversationUpdateRequest;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.domain.vo.AiAssistantOverviewVO;
import com.qiheng.erp.ai.domain.vo.AiConversationSummaryVO;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.service.IAiConversationService;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.security.context.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AiConversationServiceImpl extends ServiceImpl<AiConversationMapper, AiConversation>
        implements IAiConversationService {

    private static final int OVERVIEW_CONVERSATION_LIMIT = 50;
    private static final String DEFAULT_TITLE = "新的经营会话";
    private static final String DEFAULT_DESCRIPTION = "尚未开始分析";

    private final AiConversationMapper conversationMapper;
    private final AiMessageMapper messageMapper;

    public AiConversationServiceImpl(AiConversationMapper conversationMapper, AiMessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    @Override
    public AiAssistantOverviewVO overview() {
        Long userId = currentUserId();
        List<AiConversationSummaryVO> conversations = conversationMapper.selectList(
                        new LambdaQueryWrapper<AiConversation>()
                                .eq(AiConversation::getUserId, userId)
                                .orderByDesc(AiConversation::getLastMessageAt)
                                .orderByDesc(AiConversation::getUpdateTime)
                                .orderByDesc(AiConversation::getId)
                                .last("LIMIT " + OVERVIEW_CONVERSATION_LIMIT))
                .stream()
                .map(this::toSummary)
                .toList();

        AiAssistantOverviewVO overview = new AiAssistantOverviewVO();
        overview.setRefreshedAt(LocalDateTime.now());
        overview.setConversations(conversations);
        // 快捷任务必须由真实后端能力提供；能力未落地前明确返回空数组。
        overview.setQuickPrompts(List.of());
        return overview;
    }

    @Override
    public AiConversationSummaryVO create() {
        LocalDateTime now = LocalDateTime.now();
        AiConversation conversation = new AiConversation()
                .setUserId(currentUserId())
                .setTitle(DEFAULT_TITLE)
                .setDescription(DEFAULT_DESCRIPTION)
                .setLastMessageAt(now)
                .setCreateTime(now)
                .setUpdateTime(now)
                .setDeleted(0)
                .setVersion(0);
        conversationMapper.insert(conversation);
        return toSummary(conversation);
    }

    @Override
    public AiConversationSummaryVO update(Long conversationId, AiConversationUpdateRequest request) {
        AiConversation conversation = getOwnedConversation(conversationId);
        conversation.setTitle(request.getTitle().trim());
        conversation.setUpdateTime(LocalDateTime.now());
        if (conversationMapper.updateById(conversation) != 1) {
            // 并发删除或版本冲突时不暴露会话是否属于其他用户。
            throw new BizException(ErrorCode.AI_CONVERSATION_NOT_FOUND);
        }
        return toSummary(conversation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long conversationId) {
        Long userId = currentUserId();
        getOwnedConversation(conversationId);

        messageMapper.delete(new LambdaQueryWrapper<AiMessage>()
                .eq(AiMessage::getConversationId, conversationId)
                .eq(AiMessage::getUserId, userId));

        int deleted = conversationMapper.delete(new LambdaQueryWrapper<AiConversation>()
                .eq(AiConversation::getId, conversationId)
                .eq(AiConversation::getUserId, userId));
        if (deleted != 1) {
            throw new BizException(ErrorCode.AI_CONVERSATION_NOT_FOUND);
        }
    }

    @Override
    public AiConversation getOwnedConversation(Long conversationId) {
        if (conversationId == null) {
            throw new BizException(ErrorCode.AI_CONVERSATION_NOT_FOUND);
        }
        AiConversation conversation = conversationMapper.selectOne(
                new LambdaQueryWrapper<AiConversation>()
                        .eq(AiConversation::getId, conversationId)
                        .eq(AiConversation::getUserId, currentUserId()));
        if (conversation == null) {
            throw new BizException(ErrorCode.AI_CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    private Long currentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private AiConversationSummaryVO toSummary(AiConversation conversation) {
        AiConversationSummaryVO summary = new AiConversationSummaryVO();
        summary.setConversationId(conversation.getId());
        summary.setTitle(conversation.getTitle());
        summary.setDescription(conversation.getDescription());
        LocalDateTime updatedAt = conversation.getLastMessageAt();
        if (updatedAt == null) {
            updatedAt = conversation.getUpdateTime() != null
                    ? conversation.getUpdateTime()
                    : conversation.getCreateTime();
        }
        summary.setUpdatedAt(updatedAt);
        return summary;
    }
}
