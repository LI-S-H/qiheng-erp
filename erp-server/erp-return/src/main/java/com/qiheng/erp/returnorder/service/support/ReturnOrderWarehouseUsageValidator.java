package com.qiheng.erp.returnorder.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.returnorder.domain.entity.ReturnOrder;
import com.qiheng.erp.returnorder.domain.enums.ReturnStatus;
import com.qiheng.erp.returnorder.mapper.ReturnOrderMapper;
import com.qiheng.erp.warehouse.domain.port.WarehouseUsageValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 退货单对仓库的引用校验。
 *
 * <p>终态定义：{@code COMPLETED} 或 {@code CANCELLED} 不阻挡停用。
 * 退货单的 source_type 同时覆盖采购退货（PURCHASE_RETURN）与销售退货（SALES_RETURN）。</p>
 */
@Component
@RequiredArgsConstructor
public class ReturnOrderWarehouseUsageValidator implements WarehouseUsageValidator {

    private final ReturnOrderMapper returnOrderMapper;

    /**
     * 检查仓库是否有未完成的退货单引用。
     */
    @Override
    public boolean hasActiveReferences(Long warehouseId) {
        return returnOrderMapper.selectCount(new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getWarehouseId, warehouseId)
                .notIn(ReturnOrder::getStatus,
                        ReturnStatus.COMPLETED.name(),
                        ReturnStatus.CANCELLED.name())) > 0;
    }

    /**
     * 检查仓库是否有任意状态的退货单引用。
     */
    @Override
    public boolean hasAnyReferences(Long warehouseId) {
        return returnOrderMapper.selectCount(new LambdaQueryWrapper<ReturnOrder>()
                .eq(ReturnOrder::getWarehouseId, warehouseId)) > 0;
    }
}