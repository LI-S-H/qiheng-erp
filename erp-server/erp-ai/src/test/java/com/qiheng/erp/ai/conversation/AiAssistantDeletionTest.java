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

    /** 会话与可见消息在事务提交后释放锁，不联动原始节点记录。 */
    @Test
    void deletionCommitsBeforeUnlockAndDoesNotTouchNodeMemory() {
        AiConversationMapper conversations = mock(AiConversationMapper.class);
        AiMessageMapper messages = mock(AiMessageMapper.class);
        ConversationExecutionLock locks = mock(ConversationExecutionLock.class);
        RLock lock = mock(RLock.class);
        when(locks.of(10)).thenReturn(lock);
        when(lock.tryLock()).thenReturn(true);
        when(conversations.selectOwned(10L, 1L)).thenReturn(new AiConversation());
        PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        AiAssistantServiceImpl service = new AiAssistantServiceImpl(conversations, messages,
                new VisibleMessageCodec(new ObjectMapper()), locks, new TransactionTemplate(tx));
        service.delete(10L, 1L);
        var order = inOrder(messages, conversations, tx, lock);
        order.verify(messages).softDeleteByConversation(10L, 1L);
        order.verify(conversations).softDelete(10L);
        order.verify(tx).commit(any());
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
                new VisibleMessageCodec(new ObjectMapper()), locks, mock(TransactionTemplate.class));
        assertThatThrownBy(() -> service.delete(10L, 1L)).isInstanceOf(BizException.class);
        verifyNoInteractions(messages);
        verify(conversations, never()).softDelete(any());
        verify(lock, never()).unlock();
    }
}
