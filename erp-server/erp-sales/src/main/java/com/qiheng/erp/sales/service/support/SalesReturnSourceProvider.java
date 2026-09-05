package com.qiheng.erp.sales.service.support;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceItem;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceOrder;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceProvider;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrderItem;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderItemMapper;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 销售模块向统一退货模块提供的来源订单适配器。
 *
 * <p>该类只暴露退货模块需要的订单与明细快照，不把销售实体或 Mapper 泄露到退货模块，
 * 从而保持依赖方向为“销售依赖退货契约”。</p>
 */
@Component
@RequiredArgsConstructor
public class SalesReturnSourceProvider implements ReturnSourceProvider {

    // 搜索最大返回条数
    private static final int MAX_SEARCH_LIMIT = 50;

    private final SalesOrderMapper salesOrderMapper;

    private final SalesOrderItemMapper salesOrderItemMapper;

    /**
     * 支持的退货类型。
     */
    @Override
    public ReturnType supportsType() {
        return ReturnType.SALES_RETURN;
    }

    /**
     * 搜索可作为退货来源的销售订单（仅部分出库与全部出库的订单存在可退数量）。
     */
    @Override
    public List<ReturnSourceOrder> searchSourceOrders(String sourceOrderNo, int limit) {
        // 退货模块已校验范围；此处再次收敛上限，防止其他调用方误传过大的 LIMIT。
        int safeLimit = Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT));
        return salesOrderMapper.selectPage(new Page<>(1, safeLimit, false),
                        new LambdaQueryWrapper<SalesOrder>()
                                .like(StrUtil.isNotBlank(sourceOrderNo), SalesOrder::getSalesNo, sourceOrderNo)
                                .and(wrapper -> wrapper.eq(SalesOrder::getStatus, SalesOrderStatus.PARTIAL_OUTBOUND)
                                        .or()
                                        .eq(SalesOrder::getStatus, SalesOrderStatus.OUTBOUND_DONE))
                                .orderByDesc(SalesOrder::getCreateTime))
                .getRecords().stream()
                .map(this::toSourceOrder).toList();
    }

    /** 根据主键读取销售订单并转换为退货来源快照。 */
    @Override
    public ReturnSourceOrder getSourceOrder(Long sourceOrderId) {
        SalesOrder order = salesOrderMapper.selectById(sourceOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "销售订单不存在");
        }
        return toSourceOrder(order);
    }

    /**
     * 批量返回已实际出库的销售订单明细。
     *
     * <p>是否仍可退由退货模块统一扣除其他有效退货单的占用数量，本 Provider 只提供销售事实数据。</p>
     */
    @Override
    public Map<Long, List<ReturnSourceItem>> listSourceItems(List<Long> sourceOrderIds) {
        // 1. 校验参数是否为空
        if (sourceOrderIds == null || sourceOrderIds.isEmpty()) return Map.of();
        // 2. 批量查询销售订单明细(已出库数量大于0)
        List<SalesOrderItem> items = salesOrderItemMapper.selectList(new LambdaQueryWrapper<SalesOrderItem>()
                .in(SalesOrderItem::getSalesOrderId, sourceOrderIds)
                .gt(SalesOrderItem::getOutboundQty, 0)
                .orderByAsc(SalesOrderItem::getId));
        if (items.isEmpty()) return Map.of();
        // 销售明细已保存精度快照，不能用当前产品档案覆盖历史业务规则。
        return items.stream()
                .collect(Collectors.groupingBy(SalesOrderItem::getSalesOrderId,
                        Collectors.mapping(item -> new ReturnSourceItem(item.getId(), item.getProductId(), item.getProductCode(),
                                item.getProductName(), item.getUnitName(), item.getQuantityPrecision(), item.getOutboundQty(), item.getUnitPrice()),
                                Collectors.toList())));
    }

    /** 销售订单实体转退货来源订单快照。 */
    private ReturnSourceOrder toSourceOrder(SalesOrder order) {
        return new ReturnSourceOrder(order.getId(), order.getSalesNo(), order.getCustomerId(),
                order.getCustomerCode(), order.getCustomerName(), order.getWarehouseId(), order.getWarehouseName(),
                order.getApprovedAt() == null ? null : order.getApprovedAt().toLocalDate());
    }
}
