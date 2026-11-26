package com.qiheng.erp.ai.clarification;

/**
 * 追问流程对用户展示的固定文案。
 *
 * @author Li
 * @since 2026-11-26
 */
public final class ClarificationTexts {

    /** 连续多次无法生成合法追问时，给用户的最终说明。 */
    public static final String TERMINAL_FAILURE_MESSAGE =
            "抱歉，这次没能把需要补充的条件问清楚。请把这次要查的内容补全后发给我，"
                    + "至少说明：查什么对象（商品、客户、供应商或单据编号）、时间范围、哪个仓库，以及想看的指标。"
                    + "我会按你给的条件继续。";

    /** 终止时能解析出原问题时使用的引导语。 */
    public static final String TERMINAL_ERROR_HEADER =
            "执行向你提问的工具出现了错误，无法完成信息的补充。请回答以下问题：";

    /** 取消追问时在历史里显示的说明。 */
    public static final String CANCELLED_MESSAGE = "本次追问已取消。";

    private ClarificationTexts() {
    }
}