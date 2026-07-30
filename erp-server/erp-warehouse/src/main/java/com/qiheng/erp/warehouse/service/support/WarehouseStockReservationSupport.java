package com.qiheng.erp.warehouse.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.warehouse.domain.warehousestock.entity.WarehouseStock;
import com.qiheng.erp.warehouse.service.IWarehouseStockService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 仓库实物库存预占支持。
 *
 * <p>所有业务模块只能通过本组件增减 {@code warehouse_stock.locked_qty}，避免来源审核、
 * 手工出库和后续退货流程各自实现可用库存校验，导致预占口径不一致。</p>
 */
@Component
public class WarehouseStockReservationSupport {

    private final IWarehouseStockService warehouseStockService;

    public WarehouseStockReservationSupport(IWarehouseStockService warehouseStockService) {
        this.warehouseStockService = warehouseStockService;
    }

    /**
     * 按产品汇总应用锁定库存变更。正数预占、负数释放，且固定先释放再预占，
     * 使编辑单据时已释放的数量可以立即复用。
     */
    @Transactional(rollbackFor = Exception.class)
    public void applyLockedQtyChanges(Long warehouseId, Map<Long, Long> deltasByProduct,
                                      boolean releaseRequired, boolean reserveRequired) {
        // 校验参数是否为空
        if (deltasByProduct == null || deltasByProduct.isEmpty()
                || deltasByProduct.entrySet().stream()
                .anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "锁定库存变更参数不能为空");
        }
        // 创建和取消只需处理一种方向，避免不必要的遍历；同仓差额编辑才需要先释放再预占。
        if (releaseRequired) {
            deltasByProduct.entrySet().stream().filter(entry -> entry.getValue() < 0)
                    .forEach(entry -> applyProductLockedQtyChange(warehouseId, entry.getKey(), entry.getValue()));
        }
        if (reserveRequired) {
            deltasByProduct.entrySet().stream().filter(entry -> entry.getValue() > 0)
                    .forEach(entry -> applyProductLockedQtyChange(warehouseId, entry.getKey(), entry.getValue()));
        }
    }

    private void applyProductLockedQtyChange(Long warehouseId, Long productId, long delta) {
        // 校验商品库存是否存在
        WarehouseStock stock = warehouseStockService.getOne(
                new LambdaQueryWrapper<WarehouseStock>()
                        .eq(WarehouseStock::getWarehouseId, warehouseId)
                        .eq(WarehouseStock::getProductId, productId)
        );
        if (stock == null) {
            throw new BizException(ErrorCode.STOCK_INSUFFICIENT.getCode(), "商品库存不存在，无法锁定或释放库存");
        }
        // 校验库存是否足够
        long stockQty = stock.getStockQty() == null ? 0L : stock.getStockQty();
        long lockedQty = stock.getLockedQty() == null ? 0L : stock.getLockedQty();
        // 如果是预占调整，校验可用库存是否足够
        if (delta > 0 && stockQty - lockedQty < delta) {
            throw new BizException(ErrorCode.STOCK_INSUFFICIENT.getCode(), "商品可用库存不足，无法锁定出库数量");
        }
        // 如果是释放调整，校验锁定库存是否足够
        if (delta < 0 && lockedQty < -delta) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "商品锁定库存不足，无法释放出库预占");
        }
        // 更新锁定库存数量
        stock.setLockedQty(lockedQty + delta);
        if (!warehouseStockService.updateById(stock)) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "库存数据已被其他人修改，请刷新后重试");
        }
    }
}
