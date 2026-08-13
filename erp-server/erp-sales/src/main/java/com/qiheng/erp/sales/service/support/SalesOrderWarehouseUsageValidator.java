package com.qiheng.erp.sales.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.sales.domain.salesorder.entity.SalesOrder;
import com.qiheng.erp.sales.domain.salesorder.enums.SalesOrderStatus;
import com.qiheng.erp.sales.mapper.SalesOrderMapper;
import com.qiheng.erp.warehouse.domain.port.WarehouseUsageValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 销售订单对仓库的引用校验。
 *
 * <p>终态定义：{@code OUTBOUND_DONE} 或 {@code CANCELLED} 不阻挡停用。</p>
 */
@Component
@RequiredArgsConstructor
public class SalesOrderWarehouseUsageValidator implements WarehouseUsageValidator {

    private final SalesOrderMapper salesOrderMapper;

    /**
     * 检查仓库是否有未完成的销售订单引用。
     */
    @Override
    public boolean hasActiveReferences(Long warehouseId) {
        return salesOrderMapper.selectCount(new LambdaQueryWrapper<SalesOrder>()
                .eq(SalesOrder::getWarehouseId, warehouseId)
                .notIn(SalesOrder::getStatus,
                        SalesOrderStatus.OUTBOUND_DONE.name(),
                        SalesOrderStatus.CANCELLED.name())) > 0;
    }

    /**
     * 检查仓库是否有任意状态的销售订单引用。
     */
    @Override
    public boolean hasAnyReferences(Long warehouseId) {
        return salesOrderMapper.selectCount(new LambdaQueryWrapper<SalesOrder>()
                .eq(SalesOrder::getWarehouseId, warehouseId)) > 0;
    }
}