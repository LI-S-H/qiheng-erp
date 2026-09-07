package com.qiheng.erp.dashboard.domain.orderstage.model;

import com.qiheng.erp.dashboard.domain.orderstage.vo.DashboardOrderStagePeriodVO;
import com.qiheng.erp.dashboard.domain.orderstage.vo.DashboardOrderStageVO;

import java.util.List;

/**
 * 订单流转一次聚合查询的结果。
 *
 * <p>期间和阶段分布必须由同一时钟计算并一并返回，避免跨月边界时分别计算导致前后口径不一致。</p>
 *
 * @param period 本次统计使用的期间
 * @param stages 订单当前阶段分布
 */
public record DashboardOrderStageSnapshot(DashboardOrderStagePeriodVO period,
                                          List<DashboardOrderStageVO> stages) {
}