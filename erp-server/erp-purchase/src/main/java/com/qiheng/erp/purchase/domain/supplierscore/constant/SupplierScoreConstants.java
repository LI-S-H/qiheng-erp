package com.qiheng.erp.purchase.domain.supplierscore.constant;

/**
 * 评分常量。
 *
 * <p>集中存放评分规则版本、窗口天数、MQ 配置等常量,避免硬编码散落在业务代码里。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
public final class SupplierScoreConstants {

    private SupplierScoreConstants() {
    }

    /** 历史评分规则版本，用于识别旧评分日志。 */
    public static final String RULE_VERSION_V1 = "SCORE_V1";

    /** 金额质量率及到期订单交付事实规则。 */
    public static final String RULE_VERSION_V2 = "SCORE_V2";

    /** 评分窗口天数(质量/交付统计) */
    public static final int SCORE_WINDOW_DAYS = 180;

    /** MQ 合并窗口时长(毫秒),5 分钟 */
    public static final long MERGE_WINDOW_MS = 5L * 60L * 1000L;

    /** RocketMQ 默认延迟表中第九级为五分钟；第八级是四分钟，不能提前结束合并窗口。 */
    public static final int MQ_DELAY_LEVEL_5MIN = 9;

    /** pending 保留一天，覆盖消费重试窗口；每日校正兜底极端消息故障。 */
    public static final long PENDING_TTL_MS = 24L * 60L * 60L * 1000L;
}
