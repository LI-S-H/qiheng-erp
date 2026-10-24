package com.qiheng.erp.ai.memory.history;

import java.util.List;

/**
 * 最小只读历史来源。实现方必须在每次目录和原文查询中落实用户、会话及历史水位过滤，
 * 不能信任模型提供的消息编号，也不能把当前轮新消息混入本轮可检索历史。
 */
public interface HistorySource {

    DirectoryPage listDirectory(Scope scope, long afterSeq, int pageSize);

    List<HistoryMessage> readMessages(Scope scope, List<Long> messageIds);

    record Scope(
            long userId,
            long conversationId,
            long baselineSeq) {

        public Scope {
            if (userId <= 0 || conversationId <= 0 || baselineSeq < 0) {
                throw new IllegalArgumentException("历史检索范围不合法");
            }
        }
    }

    record DirectoryEntry(
            long messageId,
            long seq,
            String role,
            String summary) {
    }

    record DirectoryPage(
            List<DirectoryEntry> items,
            boolean hasMore,
            long nextAfterSeq) {

        public DirectoryPage {
            items = List.copyOf(items);
        }
    }

    record HistoryMessage(
            long messageId,
            long seq,
            String role,
            String content) {
    }
}
