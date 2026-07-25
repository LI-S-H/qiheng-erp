package com.qiheng.erp.warehouse.service.impl;

import cn.hutool.core.util.StrUtil;
import com.qiheng.erp.product.domain.entity.Product;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.github.yulichang.wrapper.MPJLambdaWrapper;
import com.qiheng.erp.warehouse.domain.dto.WarehouseStockPageDto;
import com.qiheng.erp.warehouse.domain.entity.WarehouseStock;
import com.qiheng.erp.warehouse.domain.enums.InventoryHealth;
import com.qiheng.erp.warehouse.domain.enums.ReservationState;
import com.qiheng.erp.warehouse.domain.vo.WarehouseStockPageVo;
import com.qiheng.erp.warehouse.domain.vo.WarehouseStockSummaryVo;
import com.qiheng.erp.warehouse.domain.vo.WarehouseStockVo;
import com.qiheng.erp.warehouse.mapper.WarehouseStockMapper;
import com.qiheng.erp.warehouse.service.IWarehouseStockService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import com.qiheng.erp.common.util.QtyUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <p>
 * 库存余额表 服务实现类
 * </p>
 *
 * @author Li
 * @since 2026-07-17
 */
@Service
@Slf4j
public class WarehouseStockServiceImpl extends ServiceImpl<WarehouseStockMapper, WarehouseStock> implements IWarehouseStockService {
    @Autowired
    private WarehouseStockMapper warehouseStockMapper;

    /**
     * 分页查询库存余额
     * @param dto 分页查询请求
     * @return 库存余额分页结果
     */
    @Override
    public WarehouseStockPageVo page(WarehouseStockPageDto dto) {
        // 构建分页对象
        Page<WarehouseStockVo> page = dto.toPage();

        // warehouseId 在 DTO 中为 String（前端防精度丢失），转为 Long 用于查询
        Long warehouseId = StrUtil.isNotBlank(dto.getWarehouseId())
                ? Long.valueOf(dto.getWarehouseId()) : null;

        // 构建查询条件
        MPJLambdaWrapper<WarehouseStock> wrapper = new MPJLambdaWrapper<WarehouseStock>()
                .selectAs(WarehouseStock::getId, WarehouseStockVo::getStockId)
                .select(WarehouseStock::getWarehouseId)
                .select(WarehouseStock::getWarehouseCode)
                .select(WarehouseStock::getWarehouseName)
                .select(WarehouseStock::getProductId)
                .select(WarehouseStock::getProductCode)
                .select(WarehouseStock::getProductName)
                .select(WarehouseStock::getUnitName)
                .select(WarehouseStock::getStockQty)
                .select(WarehouseStock::getLockedQty)
                .selectAs(Product::getSafetyStockQty, WarehouseStockVo::getSafetyStockQty)
                .select(WarehouseStock::getVersion)
                .select(WarehouseStock::getUpdateTime)
                .leftJoin(Product.class, Product::getId, WarehouseStock::getProductId)
                .eq(warehouseId != null, WarehouseStock::getWarehouseId, warehouseId)
                .like(StrUtil.isNotBlank(dto.getProductCode()), WarehouseStock::getProductCode, dto.getProductCode())
                .like(StrUtil.isNotBlank(dto.getProductName()), WarehouseStock::getProductName, dto.getProductName())
                .orderByDesc(WarehouseStock::getUpdateTime);

        // 库存健康状态过滤（派生条件，需在 SQL 层面用原始100倍值比较）
        applyInventoryHealth(wrapper, dto.getInventoryHealth());

        // 占用状态过滤（派生条件，需在 SQL 层面用原始100倍值比较）
        applyReservationState(wrapper, dto.getReservationState());

        Page<WarehouseStockVo> result = warehouseStockMapper.selectJoinPage(page, WarehouseStockVo.class, wrapper);
        List<WarehouseStockVo> records = result.getRecords();

        // 转换100倍整数为业务真实值，并计算可用库存
        convertQtyValues(records);

        // 构建汇总信息
        WarehouseStockSummaryVo summary = buildSummary(records);

        // 根据 total 判断是否有下一页
        log.info("总记录数: {}", result.getTotal());
        boolean hasNext = (long) dto.getPageNum() * dto.getPageSize() < result.getTotal();

        WarehouseStockPageVo pageVo = new WarehouseStockPageVo();
        pageVo.setRecords(records);
        pageVo.setHasNext(hasNext);
        pageVo.setPageNum(dto.getPageNum());
        pageVo.setPageSize(dto.getPageSize());
        pageVo.setSummary(summary);
        return pageVo;
    }

    /**
     * 应用库存健康状态过滤条件
     * @param wrapper MPJ查询包装器
     * @param health 库存健康状态枚举
     */
    private void applyInventoryHealth(MPJLambdaWrapper<WarehouseStock> wrapper, InventoryHealth health) {
        if (health == null) {
            return;
        }
        switch (health) {
            case NORMAL ->
                // 可用库存 > 安全库存
                    wrapper.apply("(t.stock_qty - t.locked_qty) > t1.safety_stock_qty");
            case LOW_STOCK ->
                // 0 < 可用库存 <= 安全库存
                    wrapper.apply("(t.stock_qty - t.locked_qty) > 0 AND (t.stock_qty - t.locked_qty) <= t1.safety_stock_qty");
            case NO_AVAILABLE ->
                // 当前库存 > 0 且 可用库存 = 0
                    wrapper.apply("t.stock_qty > 0 AND (t.stock_qty - t.locked_qty) = 0");
            case OUT_OF_STOCK ->
                // 当前库存 = 0
                    wrapper.apply("t.stock_qty = 0");
        }
    }

    /**
     * 应用占用状态过滤条件
     * @param wrapper MPJ查询包装器
     * @param state 占用状态枚举
     */
    private void applyReservationState(MPJLambdaWrapper<WarehouseStock> wrapper, ReservationState state) {
        if (state == null) {
            return;
        }
        switch (state) {
            case UNLOCKED ->
                // 锁定库存 = 0
                    wrapper.apply("t.locked_qty = 0");
            case PARTIALLY_LOCKED ->
                // 0 < 锁定库存 < 当前库存
                    wrapper.apply("t.locked_qty > 0 AND t.locked_qty < t.stock_qty");
            case FULLY_LOCKED ->
                // 当前库存 > 0 且 锁定库存 = 当前库存
                    wrapper.apply("t.stock_qty > 0 AND t.locked_qty = t.stock_qty");
        }
    }

    /**
     * 转换100倍整数为业务真实值，并计算可用库存
     * @param records 库存记录列表
     */
    private void convertQtyValues(List<WarehouseStockVo> records) {
        records.forEach(vo -> {
            vo.setStockQty(QtyUtil.toDecimal(vo.getStockQty()));
            vo.setLockedQty(QtyUtil.toDecimal(vo.getLockedQty()));
            vo.setSafetyStockQty(QtyUtil.toDecimal(vo.getSafetyStockQty()));
            // 计算可用库存 = 当前库存 - 锁定库存
            if (vo.getStockQty() != null && vo.getLockedQty() != null) {
                vo.setAvailableQty(vo.getStockQty().subtract(vo.getLockedQty()));
            }
        });
    }

    /**
     * 根据当前页记录计算汇总信息
     * @param records 当前页记录
     * @return 汇总信息
     */
    private WarehouseStockSummaryVo buildSummary(List<WarehouseStockVo> records) {
        WarehouseStockSummaryVo summary = new WarehouseStockSummaryVo();
        Set<Long> warehouseIds = new HashSet<>();
        Set<Long> productIds = new HashSet<>();
        int lowStockCount = 0;
        int noAvailableCount = 0;
        int lockedCount = 0;

        for (WarehouseStockVo vo : records) {
            warehouseIds.add(vo.getWarehouseId());
            productIds.add(vo.getProductId());

            BigDecimal availableQty = vo.getAvailableQty();
            BigDecimal stockQty = vo.getStockQty();
            BigDecimal safetyStockQty = vo.getSafetyStockQty();
            BigDecimal lockedQty = vo.getLockedQty();

            // LOW_STOCK: 0 < available_qty <= safety_stock_qty
            if (availableQty != null && availableQty.compareTo(BigDecimal.ZERO) > 0
                    && safetyStockQty != null && availableQty.compareTo(safetyStockQty) <= 0) {
                lowStockCount++;
            }

            // NO_AVAILABLE: stock_qty > 0 且 available_qty = 0
            if (availableQty != null && availableQty.compareTo(BigDecimal.ZERO) == 0
                    && stockQty != null && stockQty.compareTo(BigDecimal.ZERO) > 0) {
                noAvailableCount++;
            }

            // 有锁定库存记录: locked_qty > 0
            if (lockedQty != null && lockedQty.compareTo(BigDecimal.ZERO) > 0) {
                lockedCount++;
            }
        }

        summary.setWarehouseCount(warehouseIds.size());
        summary.setProductCount(productIds.size());
        summary.setLowStockCount(lowStockCount);
        summary.setNoAvailableCount(noAvailableCount);
        summary.setLockedCount(lockedCount);
        return summary;
    }
}
