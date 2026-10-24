package com.qiheng.erp.ai.service;

import com.qiheng.erp.ai.domain.vo.AssistantOverviewVO;
import com.qiheng.erp.ai.domain.vo.ConversationDetailVO;
import com.qiheng.erp.ai.domain.vo.ConversationMessagePageVO;

/**
 * AI 助手业务接口：会话管理、消息分页与会话运行状态查询。
 *
 * @author Li
 * @since 2026-10-15
 */
public interface AiAssistantService {

    /**
     * 页面首屏数据：当前用户最近的会话列表。
     *
     * @param userId 当前用户 ID
     * @return 概览数据
     */
    AssistantOverviewVO overview(Long userId);

    /**
     * 新建会话，默认标题与描述。
     *
     * @param userId 当前用户 ID
     * @return 新会话详情
     */
    ConversationDetailVO create(Long userId);

    /**
     * 会话详情。供页面重新进入时恢复进度展示。
     *
     * @param conversationId 会话 ID
     * @param userId         当前用户 ID
     * @return 会话详情
     * @throws com.qiheng.erp.common.exception.BizException 不存在 / 已删 / 非本人
     */
    ConversationDetailVO detail(Long conversationId, Long userId);

    /**
     * 重命名会话。
     *
     * @param conversationId 会话 ID
     * @param userId         当前用户 ID
     * @param title          新标题，前端已 trim，1–40 字符
     */
    void rename(Long conversationId, Long userId, String title);

    /**
     * 删除会话：同一事务内先逻辑删除消息，再逻辑删除会话。
     *
     * @param conversationId 会话 ID
     * @param userId         当前用户 ID
     */
    void delete(Long conversationId, Long userId);

    /**
     * 用户可见消息分页。
     *
     * @param conversationId 会话 ID
     * @param userId         当前用户 ID
     * @param pageNum        页码，从 1 开始
     * @param pageSize       每页大小
     * @return 分页结果
     */
    ConversationMessagePageVO pageMessages(Long conversationId, Long userId, int pageNum, int pageSize);
}
