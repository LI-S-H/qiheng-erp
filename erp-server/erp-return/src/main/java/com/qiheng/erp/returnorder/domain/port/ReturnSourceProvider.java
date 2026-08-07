package com.qiheng.erp.returnorder.domain.port;

import java.util.List;
import java.util.Map;

/**
 * 由采购或销售模块实现的来源数据提供者。
 *
 * <p>该契约只描述数据边界；退货模块不依赖具体来源模块，从而避免循环依赖。</p>
 */
public interface ReturnSourceProvider {

    /** 返回当前 Provider 唯一负责的退货方向。 */
    ReturnType supportsType();

    /**
     * 按订单号搜索来源订单。
     *
     * @param sourceOrderNo 订单号模糊条件，可为空
     * @param limit 最大返回条数
     * @return 来源订单快照
     */
    List<ReturnSourceOrder> searchSourceOrders(String sourceOrderNo, int limit);

    /** 根据主键读取单个来源订单，并在不存在时抛出业务异常。 */
    ReturnSourceOrder getSourceOrder(Long sourceOrderId);

    /**
     * 批量返回来源订单中已实际履约、可参与退货计算的明细快照。
     *
     * @param sourceOrderIds 来源订单ID列表
     * @return sourceOrderId → 该订单下的来源明细列表
     */
    Map<Long, List<ReturnSourceItem>> listSourceItems(List<Long> sourceOrderIds);
}