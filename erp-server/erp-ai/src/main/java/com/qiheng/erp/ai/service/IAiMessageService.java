package com.qiheng.erp.ai.service;

import com.qiheng.erp.ai.domain.dto.AiMessageCreateCommand;
import com.qiheng.erp.ai.domain.vo.AiChatMessageVO;
import com.qiheng.erp.common.dto.PageQuery;
import com.qiheng.erp.common.result.PageResult;
import java.util.List;

public interface IAiMessageService {
    PageResult<AiChatMessageVO> pageHistory(Long conversationId, PageQuery query);

    AiChatMessageVO append(Long conversationId, AiMessageCreateCommand command);

    AiChatMessageVO appendForUser(Long conversationId, Long userId, AiMessageCreateCommand command);

    List<AiChatMessageVO> recentForUser(Long conversationId, Long userId);
}
