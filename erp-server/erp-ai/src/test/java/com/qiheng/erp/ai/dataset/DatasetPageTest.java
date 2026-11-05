package com.qiheng.erp.ai.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 验证分页、长度边界和访问权限。 */
class DatasetPageTest {
    @TempDir Path directory;

    @Test
    void readsOnlyRequestedPageAndRechecksAccess() throws Exception {
        var repository = mock(DatasetRepository.class);
        var access = mock(DatasetAccess.class);
        var record = new DatasetRecord("ds_a", "run", 1, 2, "测试", List.of(), 3, true, "READY", null, null);
        when(repository.find("ds_a", 1, 2)).thenReturn(Optional.of(record));
        Files.writeString(directory.resolve("ds_a.jsonl"), "[1]\n[2]\n[3]\n");
        var manager = new DatasetManager(repository, access, new ObjectMapper(), directory.toString(), 0);
        var first = manager.readPage("ds_a", 1, 2, 0, 2);
        assertThat(first.rows()).containsExactly(List.of(1), List.of(2));
        assertThat(first.rowCount()).isEqualTo(3);
        assertThat(first.returnedRows()).isEqualTo(2);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.truncated()).isFalse();
        assertThat(first.nextOffset()).isEqualTo(2);
        var last = manager.readPage("ds_a", 1, 2, 2, 2);
        assertThat(last.rows()).containsExactly(List.of(3));
        assertThat(last.hasMore()).isFalse();
        var beyond = manager.readPage("ds_a", 1, 2, 4, 2);
        assertThat(beyond.rows()).isEmpty();
        assertThat(beyond.hasMore()).isFalse();
        assertThat(beyond.nextOffset()).isNull();
        assertThatThrownBy(() -> manager.readPage("ds_a", 99, 2, 0, 1)).isInstanceOf(IllegalArgumentException.class);
        verify(access, times(3)).verify(record);
    }

    @Test
    void limitsRowsAndRejectsOversizedSingleRow() throws Exception {
        var repository = mock(DatasetRepository.class);
        var record = new DatasetRecord("ds_big", "run", 1, 2, "测试", List.of(), 1, true, "READY", null, null);
        when(repository.find("ds_big", 1, 2)).thenReturn(Optional.of(record));
        Files.writeString(directory.resolve("ds_big.jsonl"), "[\"" + "x".repeat(33000) + "\"]\n");
        var manager = new DatasetManager(repository, ignored -> {}, new ObjectMapper(), directory.toString(), 0);
        assertThatThrownBy(() -> manager.readPage("ds_big", 1, 2, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> manager.readPage("ds_big", 1, 2, 0, 1)).hasMessageContaining("超出读取上限");
    }

    @Test
    void stopsAtTextBudgetAndRejectsUnpublishedResult() throws Exception {
        var repository = mock(DatasetRepository.class);
        var ready = new DatasetRecord("ds_budget", "run", 1, 2, "测试", List.of(), 2, true, "READY", null, null);
        when(repository.find("ds_budget", 1, 2)).thenReturn(Optional.of(ready));
        Files.writeString(directory.resolve("ds_budget.jsonl"), "[\"" + "x".repeat(4000) + "\"]\n[\"" + "y".repeat(4000) + "\"]\n");
        var manager = new DatasetManager(repository, ignored -> {}, new ObjectMapper(), directory.toString(), 0);
        var page = manager.readPage("ds_budget", 1, 2, 0, 2);
        assertThat(page.returnedRows()).isEqualTo(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.truncated()).isTrue();
        assertThat(page.nextOffset()).isEqualTo(1);
        var staged = new DatasetRecord("ds_budget", "run", 1, 2, "测试", List.of(), 2, true, "STAGED", null, null);
        when(repository.find("ds_budget", 1, 2)).thenReturn(Optional.of(staged));
        assertThatThrownBy(() -> manager.readPage("ds_budget", 1, 2, 0, 1)).hasMessageContaining("尚未发布");
    }

    @Test
    void smallRowsAreNotLimitedToFifty() throws Exception {
        var repository = mock(DatasetRepository.class);
        var ready = new DatasetRecord("ds_small", "run", 1, 2, "测试", List.of(), 60, true, "READY", null, null);
        when(repository.find("ds_small", 1, 2)).thenReturn(Optional.of(ready));
        Files.writeString(directory.resolve("ds_small.jsonl"), "[1]\n".repeat(60));
        var manager = new DatasetManager(repository, ignored -> {}, new ObjectMapper(), directory.toString(), 0);
        var page = manager.readPage("ds_small", 1, 2, 0, Integer.MAX_VALUE);
        assertThat(page.returnedRows()).isEqualTo(60);
        assertThat(page.truncated()).isFalse();
        assertThat(page.nextOffset()).isNull();
        assertThat(new ObjectMapper().writeValueAsString(page).length()).isLessThanOrEqualTo(8000);
    }
}
