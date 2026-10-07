package com.qiheng.erp.common.scoring;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 入库单事实查询接口(反转接口模式,定义在 erp-common)。
 *
 * <p>三个模块共享:</p>
 * <ul>
 *   <li>实现:erp-warehouse 的 InboundBillFactsQueryServiceImpl,负责查 inbound_bill + inbound_bill_item + stock_bill_item 聚合</li>
 *   <li>调用:erp-purchase 的 ScoreFactsQueryService,Java 端再聚合</li>
 *   <li>数据契约:InboundFactsView(同模块)</li>
 * </ul>
 *
 * @author Li
 * @since 2026-09-24
 */
public interface InboundBillFactsQueryService {

    /**
     * 查 supplier 180 天内已完全入库的入库单明细。
     *
     * <p>SQL 在 erp-warehouse 端实现:按入库单 ID GROUP BY 聚合明细,
     * 过滤确认时间在 [since, now] 范围、未逻辑删除的入库单。</p>
     *
     * <p>返回的明细按 confirmed_at 升序,无数据返回空列表(null 也合法)。</p>
     *
     * @param supplierId 目标供应商 ID,不允许 null
     * @param since 窗口起点(通常 = today - 180 天的 00:00:00),不允许 null
     * @return 明细视图列表
     */
    List<InboundFactsView> queryRecentFacts(Long supplierId, LocalDateTime since);
}
