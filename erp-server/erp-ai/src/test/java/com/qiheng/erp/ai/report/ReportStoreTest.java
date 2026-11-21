package com.qiheng.erp.ai.report;

import com.qiheng.erp.ai.context.AgentToolContext;
import com.qiheng.erp.ai.domain.entity.AiConversation;
import com.qiheng.erp.ai.mapper.AiConversationMapper;
import com.qiheng.erp.ai.mapper.AiMessageMapper;
import com.qiheng.erp.ai.mapper.AiReportMapper;
import com.qiheng.erp.common.exception.BizException;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ReportStoreTest {
    private final AiConversationMapper conversations = mock(AiConversationMapper.class);
    private final AiMessageMapper messages = mock(AiMessageMapper.class);
    private final AiReportMapper reports = mock(AiReportMapper.class);
    private final ReportStore store = new ReportStore(conversations, messages, reports);
    private final AgentToolContext identity = new AgentToolContext(7, 123);

    @Test
    void usesIncrementedMarkerTitleAndReturnsSameIdOnCallReplay() {
        when(messages.lockOwnedConversation(123, 7)).thenReturn(123L);
        when(reports.titleExists(123, "run-1", "库存 ［复盘］")).thenReturn(true);
        when(reports.insert(anyString(), eq(7L), eq(123L), eq("run-1"), eq("call-2"),
                eq("库存 ［复盘］（2）"), eq("正文"))).thenReturn(1);

        ReportStore.Reference saved = store.save(identity, "run-1", "call-2", "库存\n[复盘]", "正文");
        assertThat(saved.title()).isEqualTo("库存 ［复盘］（2）");
        assertThat(saved.placeholder()).isEqualTo("[[REPORT:库存 ［复盘］（2）]]");
        assertThat(saved.reportId()).startsWith("rpt_");

        when(reports.getByCall(7, 123, "run-1", "call-2")).thenReturn(new AiReportMapper.CardRow(
                saved.reportId(), "run-1", "call-2", saved.title()));
        assertThat(store.save(identity, "run-1", "call-2", "其他标题", "其他正文")).isEqualTo(saved);
        verify(reports).insert(saved.reportId(), 7, 123, "run-1", "call-2", saved.title(), "正文");
    }

    @Test
    void doesNotWriteReportIntoSomeoneElsesConversation() {
        when(messages.lockOwnedConversation(123, 7)).thenReturn(null);
        assertThatThrownBy(() -> store.save(identity, "run-1", "call-1", "标题", "正文"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(reports);
    }

    @Test
    void historyScopesAndDeduplicatesRequestIdFilters() {
        when(conversations.selectOwned(123L, 7L)).thenReturn(new AiConversation());
        when(reports.listCards(7, 123, List.of("run-1", "run-2"))).thenReturn(List.of(
                new AiReportMapper.CardRow("rpt_1", "run-1", "call-1", "库存复盘")));

        assertThat(store.cards(123, 7, List.of("run-1", "run-2", "run-1")))
                .containsExactly(new ReportStore.Reference(
                        "rpt_1", "run-1", "call-1", "库存复盘", "[[REPORT:库存复盘]]"));
        verify(reports).listCards(7, 123, List.of("run-1", "run-2"));
        verifyNoMoreInteractions(reports);
    }

    @Test
    void returnsBodyOnlyAfterMatchingOwnerAndConversation() {
        when(reports.getOwned(7, 123, "rpt_1")).thenReturn(
                new AiReportMapper.DetailRow("rpt_1", "run-1", "call-1", "库存复盘", "## 正文"));

        assertThat(store.detail(123, 7, "rpt_1").content()).isEqualTo("## 正文");
        assertThatThrownBy(() -> store.detail(123, 8, "rpt_1")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> store.detail(124, 7, "rpt_1")).isInstanceOf(BizException.class);
    }

    /** 参考列表只给内部追溯用，保存和读取时都不返回给前端。 */
    @Test
    void stripsReferencesAndCitationMarks() {
        String content = "## 主要结论\n\n库存充足。[1]\n\n## 参考资料\n\n[1] 库存数据集 ds_1";
        when(messages.lockOwnedConversation(123, 7)).thenReturn(123L);
        when(reports.insert(anyString(), eq(7L), eq(123L), eq("run-1"), eq("call-1"),
                eq("库存报告"), eq("## 主要结论\n\n库存充足。"))).thenReturn(1);

        store.save(identity, "run-1", "call-1", "库存报告", content);

        when(reports.getOwned(7, 123, "rpt_1")).thenReturn(
                new AiReportMapper.DetailRow("rpt_1", "run-1", "call-1", "库存报告", content));
        assertThat(store.detail(123, 7, "rpt_1").content()).isEqualTo("## 主要结论\n\n库存充足。");
    }
}
