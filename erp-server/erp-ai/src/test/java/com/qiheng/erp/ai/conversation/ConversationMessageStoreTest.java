package com.qiheng.erp.ai.conversation;

import com.qiheng.erp.ai.domain.entity.AiMessage;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ConversationMessageStoreTest {
    @Test
    void allocatesSequenceAndReturnsExistingMessageOnRetry() {
        var mapper = mock(AiMessageMapper.class);
        when(mapper.lockOwnedConversation(10, 20)).thenReturn(10L);
        when(mapper.maxSequence(10)).thenReturn(8L);
        var store = new ConversationMessageStore(mapper);
        var message = new AiMessage().setConversationId(10L).setUserId(20L).setMessageKey("run:user");
        assertThat(store.append(message).getSeq()).isEqualTo(9);
        verify(mapper).insert(message);

        when(mapper.selectByMessageKey(10, "run:user")).thenReturn(message);
        assertThat(store.append(new AiMessage().setConversationId(10L).setUserId(20L).setMessageKey("run:user")))
                .isSameAs(message);
        verify(mapper, times(1)).insert(any(AiMessage.class));
    }

    @Test
    void refusesMissingConversationAndUnknownRun() {
        var mapper = mock(AiMessageMapper.class);
        when(mapper.lockOwnedConversation(10, 20)).thenReturn(null);
        var store = new ConversationMessageStore(mapper);
        assertThatThrownBy(() -> store.append(new AiMessage().setConversationId(10L).setUserId(20L).setMessageKey("x")))
                .hasMessageContaining("无权访问");
        assertThatThrownBy(() -> store.updateRunStatus(20, 10, "run", "RUNNING", null))
                .hasMessageContaining("运行记录");
        verify(mapper, never()).insert(any(AiMessage.class));
    }
}
