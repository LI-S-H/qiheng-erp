package com.qiheng.erp.ai.knowledge;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间口径文本的契约测试。
 *
 * <p>滚动窗口由代码算：模型自己减天数会数出"含当天 91 天"，审阅再拿这个退一轮。</p>
 */
class TimeScopeTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-24T10:00:00+08:00");

    @Test
    void calendarWindowsAreAbsoluteRanges() {
        String text = TimeScope.text(NOW);

        assertThat(text)
                .contains("今天 2026-10-24")
                .contains("上月 2026-09-01~2026-09-30")
                .contains("本月 2026-10-01~2026-10-31")
                .contains("下月 2026-11-01~2026-11-30");
    }

    @Test
    void rollingWindowsIncludeTodayAndCountDays() {
        String text = TimeScope.text(NOW);

        assertThat(text)
                .contains("近7天 2026-10-18~2026-10-24")
                .contains("近30天 2026-09-25~2026-10-24")
                .contains("近90天 2026-07-27~2026-10-24")
                .contains("近180天 2026-04-28~2026-10-24")
                .contains("含当天、共 N 天")
                .contains("直接照抄不要自己数");
    }

    @Test
    void scopeStaysOnOneLine() {
        // 它每轮都出现，多行只是白占 token
        assertThat(TimeScope.text(NOW)).doesNotContain("\n");
    }
}
