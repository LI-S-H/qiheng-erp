package com.qiheng.erp.returnorder.domain.port;

import java.util.Collection;
import java.util.Map;

/**
 * 面向来源订单的退货审批汇总查询能力。
 *
 * <p>该契约只返回退货模块已经确认的审批事实，不判断来源订单是否已全量履约，
 * 以免退货模块反向依赖采购、销售模块的订单状态。</p>
 */
public interface ReturnOrderApprovalSummaryProvider {

    /**
     * 批量汇总同一退货方向下来源订单的退货审批事实。
     *
     * @param returnType 退货方向
     * @param sourceOrderIds 来源订单 ID 集合
     * @return 来源订单 ID 到退货审批汇总的映射；没有关联退货单的来源订单不会出现在结果中
     */
    Map<Long, ReturnSourceOrderApprovalSummary> summarize(ReturnType returnType,
                                                            Collection<Long> sourceOrderIds);
}
