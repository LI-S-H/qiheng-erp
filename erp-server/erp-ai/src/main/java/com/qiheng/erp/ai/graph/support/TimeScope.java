package com.qiheng.erp.ai.graph.support;

import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

/**
 * 时间口径：把"今天/上月/本月/下月"的起止日期算好交给模型。
 *
 * <p>相对日期让模型自己推月份边界是易错点，代码算好更可靠。planner 拆需求要用它
 * 定时间范围，审阅判"时间口径有没有说清、算得对不对"也要用它——两处必须是同一份文本，
 * 否则会出现"planner 按 A 理解、审阅按 B 判定"。</p>
 *
 * @author Li
 * @since 2026-10-24
 */
public final class TimeScope {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private TimeScope() {
    }

    /**
     * 生成时间口径文本（压成一行：它每轮都出现，值又不变，多行只是白占 token）。
     *
     * <p>滚动窗口也由代码算好：模型自己数"近 90 天含不含当天"会数出两种结果
     * （自己减 90 天得到的是 91 天），审阅还会拿这个退一轮。这里定死"含当天、共 N 天"，
     * 两边用同一串数字。</p>
     *
     * @param now 当前时间
     * @return 形如"今天 2026-10-24。上月 …，本月 …，近90天 …。相对日期按此推算。"
     */
    public static String text(OffsetDateTime now) {
        YearMonth current = YearMonth.from(now);
        Function<YearMonth, String> range = month ->
                month.atDay(1).format(DATE) + "~" + month.atEndOfMonth().format(DATE);
        return "今天 " + now.toLocalDate().format(DATE) + "。"
                + "上月 " + range.apply(current.minusMonths(1)) + "，"
                + "本月 " + range.apply(current) + "，"
                + "下月 " + range.apply(current.plusMonths(1)) + "；"
                + "近7天 " + rolling(now, 7) + "，"
                + "近30天 " + rolling(now, 30) + "，"
                + "近90天 " + rolling(now, 90) + "，"
                + "近180天 " + rolling(now, 180)
                + "（滚动窗口含当天、共 N 天，直接照抄不要自己数）。相对日期按此推算。";
    }

    /** 近 N 天：以今天为末天、含当天，往前推 N-1 天。 */
    private static String rolling(OffsetDateTime now, int days) {
        return now.toLocalDate().minusDays(days - 1L).format(DATE)
                + "~" + now.toLocalDate().format(DATE);
    }
}