package com.qiheng.erp.common.constant;

/**
 * 评分服务 Redis Key 常量。
 *
 * <p>统一管理评分相关的 Redis Key,避免散落在代码各处导致键名不一致。
 * 键值设计:</p>
 * <ul>
 *   <li>BillNoGenerator 前缀:复用业务单号生成器,按天自增</li>
 *   <li>锁键:同 supplier 评分重算串行</li>
 *   <li>pending 键:5min 合并窗口状态</li>
 *   <li>质量缓存：供应商近 180 天质量金额汇总，24 小时过期</li>
 * </ul>
 *
 * @author Li
 * @since 2026-09-23
 */
public final class SupplierScoreRedisKeys {

    private SupplierScoreRedisKeys() {
    }

    /** 评分重算批次号前缀,复用 BillNoGenerator.nextNo("SC") */
    public static final String SC_BILL_PREFIX = "SC";

    /**
     * 按 supplier 锁,保证同一 supplier 评分重算串行执行。
     * 锁粒度:supplier
     *
     * @param supplierId 供应商 ID
     * @return 供应商评分重算锁键
     */
    public static String lockKey(Long supplierId) {
        return "supplier:score:lock:" + supplierId;
    }

    /**
     * 5min 合并窗口的 pending 状态。
     * 同 supplier 第一个事件写入并投递 SCHEDULED_FIRE;
     * 后续事件只合并范围、不重置时间。
     *
     * @param supplierId 供应商 ID
     * @return 供应商事件合并状态键
     */
    public static String pendingKey(Long supplierId) {
        return "supplier:score:pending:" + supplierId;
    }

    /**
     * 供应商质量金额汇总缓存；供货产品质量分仍以数据库事实计算。
     *
     * @param supplierId 供应商 ID
     * @return 供应商质量金额缓存键
     */
    public static String qualityAmountKey(Long supplierId) {
        return "supplier:score:quality:" + supplierId;
    }

}
