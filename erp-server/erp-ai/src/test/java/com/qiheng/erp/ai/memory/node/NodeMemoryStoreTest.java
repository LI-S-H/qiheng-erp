package com.qiheng.erp.ai.memory.node;

import com.qiheng.erp.ai.constant.NodeCode;
import com.qiheng.erp.ai.domain.entity.AiNodeMemory;
import com.qiheng.erp.ai.domain.entity.AiNodeWorkingMemory;
import com.qiheng.erp.ai.mapper.AiNodeMemoryMapper;
import com.qiheng.erp.ai.mapper.AiNodeWorkingMemoryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NodeMemoryStoreTest {

    private final AiNodeMemoryMapper raw = mock(AiNodeMemoryMapper.class);

    private final AiNodeWorkingMemoryMapper working = mock(AiNodeWorkingMemoryMapper.class);

    private final NodeMemoryStore store = new NodeMemoryStore(raw, working);

    @Test
    void appendTranscriptWritesEventAndPlainTextRow() {
        when(raw.selectMaxSeq(1L, NodeCode.PLANNER)).thenReturn(8L);
        when(working.selectMaxSortOrder(1L, NodeCode.PLANNER)).thenReturn(30L);
        when(raw.insert(any(AiNodeMemory.class))).thenAnswer(invocation -> {
            invocation.<AiNodeMemory>getArgument(0).setId(99L);
            return 1;
        });

        store.appendTranscript(1L, 2L, NodeCode.PLANNER, "{\"eventType\":\"USER_INPUT\"}", "[输入] 第 1 轮");

        ArgumentCaptor<AiNodeMemory> flow = ArgumentCaptor.forClass(AiNodeMemory.class);
        verify(raw).insert(flow.capture());
        assertEquals(9L, flow.getValue().getSeq());
        assertEquals("{\"eventType\":\"USER_INPUT\"}", flow.getValue().getContent());

        ArgumentCaptor<AiNodeWorkingMemory> saved = ArgumentCaptor.forClass(AiNodeWorkingMemory.class);
        verify(working).insert(saved.capture());
        assertEquals(31L, saved.getValue().getSortOrder());
        // 工作记忆存纯文本转写，不再是事件 JSON
        assertEquals("[输入] 第 1 轮", saved.getValue().getContent());
        assertEquals("[99]", saved.getValue().getSourceMemoryIds());
    }

    @Test
    void appendEventOnlyWritesImmutableOriginal() {
        when(raw.selectMaxSeq(1L, NodeCode.PLANNER)).thenReturn(3L);

        store.appendEvent(1L, 2L, NodeCode.PLANNER, "{\"eventType\":\"CLARIFICATION_ASKED\"}");

        ArgumentCaptor<AiNodeMemory> flow = ArgumentCaptor.forClass(AiNodeMemory.class);
        verify(raw).insert(flow.capture());
        assertEquals(4L, flow.getValue().getSeq());
        verify(working, never()).insert(any(AiNodeWorkingMemory.class));
    }

    @Test
    void replacementUsesFreshOrderAndNeverModifiesOriginalTable() {
        AiNodeWorkingMemory previous = new AiNodeWorkingMemory().setId(10L)
                .setConversationId(1L).setUserId(2L).setNodeCode(NodeCode.PLANNER);
        when(working.selectMaxSortOrder(1L, NodeCode.PLANNER)).thenReturn(80L);
        when(working.softDeleteWindow(1L, 2L, NodeCode.PLANNER, List.of(10L))).thenReturn(1);

        store.replaceWorkingMemory(1L, 2L, NodeCode.PLANNER, List.of(previous), List.of(
                new AiNodeWorkingMemory()
                        .setContent("{\"text\":\"摘要\"}").setSourceMemoryIds("[99]")));

        ArgumentCaptor<AiNodeWorkingMemory> saved = ArgumentCaptor.forClass(AiNodeWorkingMemory.class);
        verify(working).insert(saved.capture());
        assertEquals(81L, saved.getValue().getSortOrder());
        assertEquals("[99]", saved.getValue().getSourceMemoryIds());
        assertEquals(2L, saved.getValue().getUserId());
        verifyNoInteractions(raw);
    }

    @Test
    void changedWindowAndForeignOwnershipAbortReplacement() {
        AiNodeWorkingMemory previous = new AiNodeWorkingMemory().setId(10L)
                .setConversationId(1L).setUserId(2L).setNodeCode(NodeCode.PLANNER);
        List<AiNodeWorkingMemory> replacement = List.of(new AiNodeWorkingMemory().setContent("{}"));

        assertThrows(IllegalStateException.class, () -> store.replaceWorkingMemory(
                1L, 2L, NodeCode.PLANNER, List.of(previous), replacement));
        previous.setUserId(3L);
        assertThrows(IllegalArgumentException.class, () -> store.replaceWorkingMemory(
                1L, 2L, NodeCode.PLANNER, List.of(previous), replacement));
        verify(working, never()).insert(any(AiNodeWorkingMemory.class));
        verifyNoInteractions(raw);
    }
}
