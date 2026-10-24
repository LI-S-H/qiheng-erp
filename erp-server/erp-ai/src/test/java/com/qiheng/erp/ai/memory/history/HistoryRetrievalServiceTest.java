package com.qiheng.erp.ai.memory.history;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiheng.erp.ai.memory.history.HistorySource.DirectoryEntry;
import com.qiheng.erp.ai.memory.history.HistorySource.DirectoryPage;
import com.qiheng.erp.ai.memory.history.HistorySource.HistoryMessage;
import com.qiheng.erp.ai.memory.history.HistorySource.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HistoryRetrievalServiceTest {

    private final HistorySource source = mock(HistorySource.class);
    private final ChatModel model = mock(ChatModel.class);
    private final Scope scope = new Scope(7, 11, 100);
    private final HistoryRetrievalService service = new HistoryRetrievalService(source, model, new ObjectMapper());

    @Test
    void passesTrustedScopeToDirectoryAndOriginalReadsAndReportsPartialScan() {
        when(source.listDirectory(scope, 0, 20)).thenReturn(new DirectoryPage(List.of(
                new DirectoryEntry(31, 1, "user", "询问仓库A库存")), true, 1));
        when(model.call(any(Prompt.class))).thenReturn(response("{\"messageIds\":[31]}"));
        HistoryMessage original = new HistoryMessage(31, 1, "user", "请分析仓库A库存");
        when(source.readMessages(scope, List.of(31L))).thenReturn(List.of(original));

        HistoryRetrievalService.Result result = service.retrieve("仓库A", scope, 3, 1);

        assertEquals(List.of(original), result.messages());
        assertEquals(scope, result.scope());
        assertTrue(result.hasMore());
        assertEquals(1, result.scannedPages());
        assertEquals(1, result.nextAfterSeq());
        verify(source).listDirectory(scope, 0, 20);
        verify(source).readMessages(scope, List.of(31L));
        verifyNoMoreInteractions(source);
    }

    @Test
    void rejectsModelSelectedIdOutsideAuthorizedDirectoryBeforeReadingOriginal() {
        when(source.listDirectory(scope, 0, 20)).thenReturn(new DirectoryPage(List.of(
                new DirectoryEntry(31, 1, "user", "仓库A")), false, 1));
        when(model.call(any(Prompt.class))).thenReturn(response("{\"messageIds\":[999]}"));
        assertThrows(IllegalStateException.class, () -> service.retrieve("仓库A", scope, 3, 1));
        verify(source, never()).readMessages(any(), any());
    }

    @Test
    void rejectsNonAdvancingPaginationAndHistoryPastBaseline() {
        when(source.listDirectory(scope, 0, 20)).thenReturn(new DirectoryPage(List.of(), true, 0));
        assertThrows(IllegalStateException.class, () -> service.retrieve("仓库A", scope, 3, 2));
        when(source.listDirectory(scope, 0, 20)).thenReturn(new DirectoryPage(List.of(
                new DirectoryEntry(31, 101, "user", "越界")), false, 101));
        assertThrows(IllegalStateException.class, () -> service.retrieve("仓库A", scope, 3, 2));
        verifyNoInteractions(model);
    }

    @Test
    void noMatchingDirectoryDoesNotReadOriginalAndCanContinueFromCursor() {
        when(source.listDirectory(scope, 20, 20)).thenReturn(new DirectoryPage(List.of(
                new DirectoryEntry(31, 21, "user", "采购")), false, 21));
        when(model.call(any(Prompt.class))).thenReturn(response("{\"messageIds\":[]}"));
        HistoryRetrievalService.Result result = service.retrieve("库存", scope, 3, 1, 20);
        assertTrue(result.messages().isEmpty());
        assertFalse(result.hasMore());
        assertEquals(21, result.nextAfterSeq());
        verify(source, never()).readMessages(any(), any());
    }

    private static ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }
}
