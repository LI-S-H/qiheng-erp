package com.qiheng.erp.purchase.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrderItem;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.mapper.PurchaseOrderItemMapper;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceItem;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceOrder;
import com.qiheng.erp.returnorder.domain.port.ReturnSourceProvider;
import com.qiheng.erp.returnorder.domain.port.ReturnType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 采购模块向统一退货模块提供的来源订单适配器。
 *
 * <p>该类只暴露退货模块需要的订单与明细快照，不把采购实体或 Mapper 泄露到退货模块，
 * 从而保持依赖方向为“采购依赖退货契约”。</p>
 */
@Component
@RequiredArgsConstructor
public class PurchaseReturnSourceProvider implements ReturnSourceProvider {


    // 搜索最大返回条数
    private static final int MAX_SEARCH_LIMIT = 50;

    private final PurchaseOrderMapper purchaseOrderMapper;

    private final PurchaseOrderItemMapper purchaseOrderItemMapper;

    /**
     * 支持的退货类型。
     */
    @Override
    public ReturnType supportsType() {
        return ReturnType.PURCHASE_RETURN;
    }

    /**
     * 搜索可作为退货来源的采购订单。
     */
    @Override
    public List<ReturnSourceOrder> searchSourceOrders(String sourceOrderNo, int limit) {
        // 退货模块已校验范围；此处再次收敛上限，防止其他调用方误传过大的 LIMIT。
        int safeLimit = Math.max(1, Math.min(limit, MAX_SEARCH_LIMIT));
        return purchaseOrderMapper.selectPage(new Page<>(1, safeLimit, false),
                        new LambdaQueryWrapper<PurchaseOrder>()
                                .like(StrUtil.isNotBlank(sourceOrderNo), PurchaseOrder::getPurchaseNo, sourceOrderNo)
                                .and(wrapper -> wrapper.eq(PurchaseOrder::getStatus, PurchaseOrderStatus.PARTIAL_INBOUND)
                                        .or()
                                        .eq(PurchaseOrder::getStatus, PurchaseOrderStatus.INBOUND_DONE))
                                .orderByDesc(PurchaseOrder::getCreateTime))
                .getRecords().stream()
                .map(order -> new ReturnSourceOrder(order.getId(), order.getPurchaseNo(), order.getSupplierId(),
                        order.getSupplierCode(), order.getSupplierName(), order.getWarehouseId(), order.getWarehouseName())).toList();
    }

    /** 根据主键读取采购订单并转换为退货来源快照。 */
    @Override
    public ReturnSourceOrder getSourceOrder(Long sourceOrderId) {
        PurchaseOrder order = purchaseOrderMapper.selectById(sourceOrderId);
        if (order == null) {
            throw new BizException(ErrorCode.DATA_NOT_FOUND.getCode(), "采购订单不存在");
        }
        return new ReturnSourceOrder(order.getId(), order.getPurchaseNo(), order.getSupplierId(), order.getSupplierCode(),
                order.getSupplierName(), order.getWarehouseId(), order.getWarehouseName());
    }

    /**
     * 批量返回已实际入库的采购订单明细。
     *
     * <p>是否仍可退由退货模块统一扣除其他有效退货单的占用数量，本 Provider 只提供采购事实数据。</p>
     */
    @Override
    public Map<Long, List<ReturnSourceItem>> listSourceItems(List<Long> sourceOrderIds) {
        // 1. 校验参数是否为空
        if (sourceOrderIds == null || sourceOrderIds.isEmpty()) return Map.of();
        // 2. 批量查询采购订单明细(已入库数量大于0)
        List<PurchaseOrderItem> items = purchaseOrderItemMapper.selectList(new LambdaQueryWrapper<PurchaseOrderItem>()
                .in(PurchaseOrderItem::getPurchaseOrderId, sourceOrderIds)
                .gt(PurchaseOrderItem::getInboundQty, 0)
                .orderByAsc(PurchaseOrderItem::getId));
        if (items.isEmpty()) return Map.of();
        // 精度必须使用采购明细快照，避免产品档案修改影响历史退货规则。
        return items.stream()
                .collect(Collectors.groupingBy(PurchaseOrderItem::getPurchaseOrderId,
                        Collectors.mapping(item -> new ReturnSourceItem(item.getId(), item.getProductId(), item.getProductCode(),
                                item.getProductName(), item.getUnitName(), item.getQuantityPrecision(), item.getInboundQty(), item.getUnitPrice()),
                                Collectors.toList())));
    }
}
