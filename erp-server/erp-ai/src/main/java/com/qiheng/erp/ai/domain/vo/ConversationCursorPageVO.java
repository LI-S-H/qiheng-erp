package com.qiheng.erp.ai.domain.vo;

import java.util.List;

/** 会话活动时间游标页。 */
public record ConversationCursorPageVO(List<ConversationSummaryVO> records, boolean hasMore,
                                       String nextBeforeTime, String nextBeforeId) {
}
