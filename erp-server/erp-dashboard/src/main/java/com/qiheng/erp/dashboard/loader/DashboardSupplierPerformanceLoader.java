package com.qiheng.erp.dashboard.loader;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.dashboard.domain.supplier.vo.DashboardSupplierPerformanceVO;
import com.qiheng.erp.purchase.domain.supplier.entity.Supplier;
import com.qiheng.erp.purchase.mapper.SupplierMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 工作台供应商履约评分聚合器。
 *
 * <p>直接读取 {@code supplier} 主表的综合评分（overall_score / delivery_score / quality_score /
 * price_score / service_score / on_time_rate），评分字段按 ×100 存储，接口展示为业务百分数（保留 1 位）。</p>
 *
 * <p>按 {@code overall_score} 降序取前 N 条，工作台建议返回最多 10 条以内。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Component
@RequiredArgsConstructor
public class DashboardSupplierPerformanceLoader {

    private static final int TOP_LIMIT = 10;

    private final SupplierMapper supplierMapper;

    /**
     * 加载供应商履约评分
     * @return 按综合评分降序的供应商列表
     */
    public List<DashboardSupplierPerformanceVO> load() {
        List<Supplier> suppliers = supplierMapper.selectList(
                new LambdaQueryWrapper<Supplier>()
                        .eq(Supplier::getStatus, 1)
                        .orderByDesc(Supplier::getOverallScore)
                        .last("LIMIT " + TOP_LIMIT));
        return suppliers.stream().map(this::toVO).toList();
    }

    private DashboardSupplierPerformanceVO toVO(Supplier supplier) {
        DashboardSupplierPerformanceVO vo = new DashboardSupplierPerformanceVO();
        vo.setSupplierId(supplier.getId());
        vo.setSupplierCode(supplier.getSupplierCode());
        vo.setSupplierName(supplier.getSupplierName());
        vo.setOverallScore(toPercent(supplier.getOverallScore()));
        vo.setDeliveryScore(toPercent(supplier.getDeliveryScore()));
        vo.setQualityScore(toPercent(supplier.getQualityScore()));
        vo.setPriceScore(toPercent(supplier.getPriceScore()));
        vo.setServiceScore(toPercent(supplier.getServiceScore()));
        vo.setOnTimeRate(toPercent(supplier.getOnTimeRate()));
        return vo;
    }

    /** 数据库存储 ×100 转业务百分数（保留 1 位） */
    private static BigDecimal toPercent(Integer stored) {
        if (stored == null) {
            return BigDecimal.ZERO.setScale(1, RoundingMode.HALF_UP);
        }
        return QtyUtil.toDecimal(stored).setScale(1, RoundingMode.HALF_UP);
    }
}