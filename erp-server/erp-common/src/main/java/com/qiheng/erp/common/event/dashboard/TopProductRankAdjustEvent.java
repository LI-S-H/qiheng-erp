package com.qiheng.erp.common.event.dashboard;

import java.time.LocalDate;
import java.util.List;

/**
 * 工作台 TOP 商品排行调整事件。
 *
 * <p>销售审核 / 取消、退货审核 / 取消时发布，
 * {@code erp-dashboard} 监听器在事务提交后增量维护 Redis 排行缓存。
 * 事件字段使用本模块的简单 record，避免跨模块依赖业务实体。</p>
 *
 * <p>{@code deltaSign} 直接表示加减方向：
 * <ul>
 *   <li>{@code +1}：销售审核通过、退货单取消（金额回到排行）</li>
 *   <li>{@code -1}：销售取消、退货审核通过（金额移出排行）</li>
 * </ul>
 * 监听器收到 {@code -1} 且 {@code businessDate} 早于 30 天窗口起点则不处理，
 * 由凌晨 SQL 全量重建兜底。</p>
 *
 * @author Li
 * @since 2026-09-02
 */
public record TopProductRankAdjustEvent(
        LocalDate businessDate,
        int deltaSign,
        List<RankItemInput> items
) {

    /**
     * 排行调整输入项（商品 ID、金额分值、数量分值）。
     * 由业务模块在 publishEvent 前从各自的明细实体映射而来。
     */
    public record RankItemInput(Long productId, Long amount, Long quantity) {}
}