package com.qiheng.erp.ai.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.conversation.VisibleMessageCodec;
import com.qiheng.erp.ai.conversation.ConversationExecutionLock;
import com.qiheng.erp.common.exception.BizException;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AiAssistantDeletionTest {

    /** 会话、消息与执行流水一并删除，Redis 清理发生在事务提交之后。 */
    @Test
    void deletionRemovesMessagesAndTraceThenClearsCheckpoints() {
        AiConversationMapper conversations = mock(AiConversationMapper.class);
        AiMessageMapper messages = mock(AiMessageMapper.class);
        ConversationExecutionLock locks = mock(ConversationExecutionLock.class);
        RLock lock = mock(RLock.class);
        when(locks.of(10)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(conversations.selectOwned(10L, 1L)).thenReturn(new AiConversation());
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var traces = mock(com.qiheng.erp.ai.mapper.AiAgentTraceMapper.class);
        var cleaner = mock(ConversationCheckpointCleaner.class);
        AiAssistantServiceImpl service = new AiAssistantServiceImpl(conversations, messages,
                new VisibleMessageCodec(new ObjectMapper()), locks, new TransactionTemplate(tx), traces, cleaner);
        service.delete(10L, 1L);
        var order = inOrder(messages, traces, conversations, tx, cleaner, lock);
        order.verify(messages).softDeleteByConversation(10L, 1L);
        order.verify(traces).deleteByConversation(1L, 10L);
        order.verify(conversations).softDelete(10L);
        order.verify(tx).commit(any());
        // 事务提交后才清 Redis，避免缓存抖动导致删不掉会话。
        order.verify(cleaner).clearConversation(1L, 10L);
        order.verify(lock).unlock();
    }

    /** 未取得会话锁时拒绝删除，不写入数据库。 */
    @Test
    void busyConversationCannotBeDeleted() {
        AiConversationMapper conversations = mock(AiConversationMapper.class);
        AiMessageMapper messages = mock(AiMessageMapper.class);
        ConversationExecutionLock locks = mock(ConversationExecutionLock.class);
        RLock lock = mock(RLock.class);
        when(locks.of(10)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(false);
        when(conversations.selectOwned(10L, 1L)).thenReturn(new AiConversation());
        AiAssistantServiceImpl service = new AiAssistantServiceImpl(conversations, messages,
                new VisibleMessageCodec(new ObjectMapper()), locks, mock(TransactionTemplate.class), org.mockito.Mockito.mock(com.qiheng.erp.ai.mapper.AiAgentTraceMapper.class), org.mockito.Mockito.mock(ConversationCheckpointCleaner.class));
        assertThatThrownBy(() -> service.delete(10L, 1L)).isInstanceOf(BizException.class);
        verifyNoInteractions(messages);
        verify(conversations, never()).softDelete(any());
        verify(lock, never()).unlock();
    }
}
