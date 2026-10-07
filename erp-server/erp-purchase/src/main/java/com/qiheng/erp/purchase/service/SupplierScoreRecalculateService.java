package com.qiheng.erp.purchase.service;

import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreRecalcContext;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreRecalcResult;
import java.time.LocalDate;

/**
 * 评分重算入口。
 *
 * <p>各触发场景使用独立入口，避免无关指标重复查询和计算：</p>
 * <ul>
 *   <li>A1 服务分人工调整 → recalcForServiceScoreChange</li>
 *   <li>A2 单个报价调整 → recalcForQuoteChange</li>
 *   <li>A3 参考价变更 → recalcForProductReferencePriceChange</li>
 *   <li>报价到期 → recalcPricesForSupplier</li>
 *   <li>完全入库 / 每日校正 → recalcForSupplier 或 recalcFactsForSupplier</li>
 * </ul>
 *
 * <p>同步入口要求已有业务事务：服务分、报价由业务方先取得供应商行锁；
 * 参考价入口按供应商 ID 升序取得行锁。报价到期和事实重算由消费者或定时任务
 * 在外层持有供应商 Redis 锁后调用，本 Service 在事务开头取得供应商行锁。
 * 行锁覆盖读取、计算、写回直到提交；人工服务分由事实重算保留。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
public interface SupplierScoreRecalculateService {

    /**
     * 服务分人工调整后重算。
     * <p>重算：供应商综合分、全部供货产品推荐分。</p>
     * <p>不重算：供应商质量分/交付分/价格分、供货产品质量分/价格分（不查180天事实）。</p>
     * <p>服务分由调用方先写库，此处只读新值参与聚合。</p>
     *
     * @param supplierId 目标供应商 ID
     * @param beforeServiceScore 更新前的服务分，可为空
     * @param reason 本次人工调整原因
     */
    void recalcForServiceScoreChange(Long supplierId, Integer beforeServiceScore, String reason);

    /**
     * 单个供货产品报价调整后重算。
     * <p>重算：该供货产品价格分及推荐分、供应商价格汇总及综合分。</p>
     * <p>不重算：供应商质量分/交付分/服务分、其他供货产品（不查180天事实）。</p>
     *
     * @param supplierProductId 触发报价调整的供货产品 ID
     * @param reason 本次报价调整原因，清空报价也需要保留
     */
    void recalcForQuoteChange(Long supplierProductId, String reason);

    /**
     * 产品参考采购价变更后重算（跨供应商）。
     * <p>重算：所有供应商下该产品的供货价格分及推荐分、各供应商价格汇总及综合分。</p>
     * <p>不重算：供应商质量分/交付分/服务分（不查180天事实）。</p>
     * <p>按供应商ID升序取得行锁，防止死锁。</p>
     *
     * @param productId 参考采购价发生变化的产品 ID
     */
    void recalcForProductReferencePriceChange(Long productId);

    /**
     * 报价到期扫描时重算。
     * <p>重算：该供应商全部供货产品价格分及推荐分、供应商价格汇总及综合分。</p>
     * <p>不重算：供应商质量分/交付分/服务分（不查180天事实）。</p>
     *
     * @param supplierId 目标供应商 ID
     */
    void recalcPricesForSupplier(Long supplierId);

    /**
     * 完全入库触发的事实评分重算（业务日期取上海时区当天）。
     * <p>重算：供应商质量分/价格汇总/综合分、受影响供货产品质量分/价格分/推荐分。</p>
     * <p>交付分：正常入库沿用已有，缺失时补查；全量校正时重算。</p>
     * <p>不重算：人工服务分（保留原值）。</p>
     * <p>完全入库上下文必须携带非空的合并订单集合，由外层消费者持供应商锁后调用。</p>
     *
     * @param context 重算上下文，包含供应商、触发来源及操作信息
     * @return 评分变化结果，包含供应商 ID、变化标记及指标变化明细
     */
    ScoreRecalcResult recalcForSupplier(ScoreRecalcContext context);

    /**
     * 事实评分重算（核心入口，指定业务日期）。
     * <p>重算：供应商质量分/价格汇总/综合分、受影响供货产品质量分/价格分/推荐分。</p>
     * <p>交付分：正常入库沿用已有，缺失时补查；冷启动/每日校正/异常回退时全量重算。</p>
     * <p>不重算：人工服务分（保留原值）。</p>
     * <p>正常入库只查受影响产品质量窗口，增量追加质量总额；</p>
     * <p>冷启动/跨日/异常回退/每日校正完整重建180天事实缓存，交付分也全量重算。</p>
     * <p>入库触发缺失完成订单集合时报参数错误，不从日志来源推断，也不静默转为全量。</p>
     *
     * @param context 重算上下文，包含供应商、触发来源及操作信息
     * @param businessDate 评分窗口及逾期计算使用的业务日期
     * @return 评分变化结果，包含供应商 ID、变化标记及指标变化明细
     */
    ScoreRecalcResult recalcFactsForSupplier(ScoreRecalcContext context, LocalDate businessDate);

}