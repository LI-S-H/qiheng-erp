package com.qiheng.erp.purchase.domain.supplierscore.enums;

/**
 * 重算触发来源。
 *
 * <p>写入 {@code supplier_score_change_log.trigger_type} 字段。
 * 只回答"为什么这次重算",具体业务事件不单独成枚举,细节写到日志 {@code reason} 字段。</p>
 *
 * <ul>
 *   <li>PRICE_TRIGGER / SERVICE_TRIGGER:同步路径,由业务 Service 在事务内触发</li>
 *   <li>INBOUND_TRIGGER / QUOTE_EXPIRED_TRIGGER / DAILY_TRIGGER:异步路径,由 MQ Consumer 或定时任务触发</li>
 *   <li>MERGED:5min 合并窗口内多类事件合并执行</li>
 * </ul>
 *
 * @author Li
 * @since 2026-09-23
 */
public enum TriggerType {
    /** 价格类触发:报价变化、报价有效期变化、参考价变化 */
    PRICE_TRIGGER,
    /** 人工调整服务分 */
    SERVICE_TRIGGER,
    /** 完全入库触发 */
    INBOUND_TRIGGER,
    /** 报价过期扫描触发(D1 定时任务) */
    QUOTE_EXPIRED_TRIGGER,
    /** 每日兜底核对触发(D2 定时任务) */
    DAILY_TRIGGER,
    /** 5min 窗口期内多类事件合并 */
    MERGED
}
