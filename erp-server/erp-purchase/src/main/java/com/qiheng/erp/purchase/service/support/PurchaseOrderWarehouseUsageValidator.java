package com.qiheng.erp.purchase.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.purchase.domain.purchaseorder.entity.PurchaseOrder;
import com.qiheng.erp.purchase.domain.purchaseorder.enums.PurchaseOrderStatus;
import com.qiheng.erp.purchase.mapper.PurchaseOrderMapper;
import com.qiheng.erp.warehouse.domain.port.WarehouseUsageValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 采购订单对仓库的引用校验。
 *
 * <p>终态定义：{@code INBOUND_DONE} 或 {@code CANCELLED} 不阻挡停用。</p>
 */
@Component
@RequiredArgsConstructor
public class PurchaseOrderWarehouseUsageValidator implements WarehouseUsageValidator {

    private final PurchaseOrderMapper purchaseOrderMapper;

    /**
     * 检查仓库是否有未完成的采购订单引用。
     */
    @Override
    public boolean hasActiveReferences(Long warehouseId) {
        return purchaseOrderMapper.selectCount(new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getWarehouseId, warehouseId)
                .notIn(PurchaseOrder::getStatus,
                        PurchaseOrderStatus.INBOUND_DONE.name(),
                        PurchaseOrderStatus.CANCELLED.name())) > 0;
    }

    /**
     * 检查仓库是否有任意状态的采购订单引用。
     */
    @Override
    public boolean hasAnyReferences(Long warehouseId) {
        return purchaseOrderMapper.selectCount(new LambdaQueryWrapper<PurchaseOrder>()
                .eq(PurchaseOrder::getWarehouseId, warehouseId)) > 0;
    }
}