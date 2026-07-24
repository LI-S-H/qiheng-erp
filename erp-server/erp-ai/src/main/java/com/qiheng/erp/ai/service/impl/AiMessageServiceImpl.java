package com.qiheng.erp.ai.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.dto.AiMessageCreateCommand;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.domain.vo.AiChatMessageVO;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.service.IAiConversationService;
import com.qiheng.erp.ai.service.IAiMessageService;
import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.result.PageResult;
import com.qiheng.erp.security.context.UserContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
public class AiMessageServiceImpl extends ServiceImpl<AiMessageMapper, AiMessage>
        implements IAiMessageService {

    private final AiMessageMapper messageMapper;
    private final AiConversationMapper conversationMapper;
    private final IAiConversationService conversationService;
    private final ObjectMapper objectMapper;

    public AiMessageServiceImpl(AiMessageMapper messageMapper,
                                AiConversationMapper conversationMapper,
                                IAiConversationService conversationService,
                                ObjectMapper objectMapper) {
        this.messageMapper = messageMapper;
        this.conversationMapper = conversationMapper;
        this.conversationService = conversationService;
        this.objectMapper = objectMapper;
    }

    @Override
    public PageResult<AiChatMessageVO> pageHistory(Long conversationId, PageQuery query) {
        conversationService.getOwnedConversation(conversationId);
        Long userId = currentUserId();
        Page<AiMessage> page = messageMapper.selectPage(
                query.toPage(),
                new LambdaQueryWrapper<AiMessage>()
                        .eq(AiMessage::getConversationId, conversationId)
                        .eq(AiMessage::getUserId, userId)
                        .orderByAsc(AiMessage::getCreateTime)
                        .orderByAsc(AiMessage::getId));
        List<AiChatMessageVO> records = page.getRecords().stream().map(this::toVO).toList();
        return PageResult.of(records, (int) page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AiChatMessageVO append(Long conversationId, AiMessageCreateCommand command) {
        AiConversation conversation = conversationService.getOwnedConversation(conversationId);
        Long userId = currentUserId();
        String role = normalizeRole(command.getRole());
        if (!StringUtils.hasText(command.getContent())) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }

        LocalDateTime now = LocalDateTime.now();
        AiMessage message = new AiMessage()
                .setConversationId(conversationId)
                .setUserId(userId)
                .setRole(role)
                .setContent(command.getContent().trim())
                .setChartsJson(defaultArray(command.getChartsJson()))
                .setActionCardsJson(defaultArray(command.getActionCardsJson()))
                .setSourcesJson(defaultArray(command.getSourcesJson()))
                .setAgentTracesJson(defaultArray(command.getAgentTracesJson()))
                .setTaskCardJson(command.getTaskCardJson())
                .setWorkbenchJson(command.getWorkbenchJson())
                .setCreateTime(now)
                .setUpdateTime(now)
                .setDeleted(0);
        messageMapper.insert(message);

        conversation.setLastMessageAt(now);
        conversation.setUpdateTime(now);
        if (conversationMapper.updateById(conversation) != 1) {
            throw new BizException(ErrorCode.AI_CONVERSATION_NOT_FOUND);
        }
        return toVO(message);
    }

    private String normalizeRole(String role) {
        if (!StringUtils.hasText(role)) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        String normalized = role.trim().toLowerCase(Locale.ROOT);
        if (!"user".equals(normalized) && !"assistant".equals(normalized)) {
            throw new BizException(ErrorCode.PARAM_ERROR);
        }
        return normalized;
    }

    private String defaultArray(String json) {
        return StringUtils.hasText(json) ? json : "[]";
    }

    private Long currentUserId() {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private AiChatMessageVO toVO(AiMessage message) {
        AiChatMessageVO vo = new AiChatMessageVO();
        vo.setMessageId(message.getId());
        vo.setRole(message.getRole());
        vo.setContent(message.getContent());
        vo.setCreatedAt(message.getCreateTime());
        vo.setCharts(parseArray(message.getChartsJson()));
        vo.setActionCards(parseArray(message.getActionCardsJson()));
        vo.setSources(parseArray(message.getSourcesJson()));
        vo.setAgentTraces(parseArray(message.getAgentTracesJson()));
        vo.setTaskCard(parseNullableObject(message.getTaskCardJson()));
        vo.setWorkbench(parseNullableObject(message.getWorkbenchJson()));
        return vo;
    }

    private JsonNode parseArray(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createArrayNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isArray()) {
                throw new BizException(ErrorCode.OPERATION_FAILED);
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new BizException(ErrorCode.OPERATION_FAILED);
        }
    }

    private JsonNode parseNullableObject(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.isNull()) {
                return null;
            }
            if (!node.isObject()) {
                throw new BizException(ErrorCode.OPERATION_FAILED);
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new BizException(ErrorCode.OPERATION_FAILED);
        }
    }
}
