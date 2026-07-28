package com.qiheng.erp.warehouse.service;

import cn.hutool.core.util.StrUtil;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.warehouse.domain.support.StockBillDetailVoMapping;
import com.qiheng.erp.warehouse.domain.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.enums.StockBillStatus;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.function.Supplier;

/**
 * 入库单/出库单共享逻辑帮助类。
 * <p>
 * 抽取两个方向高度重叠的：详情 VO 转换、数量摘要构建、分页汇总统计、库存变动前/后数量推导。
 * 查询条件构建与插入后时间字段补查等实体访问职责仍保留在各自的 ServiceImpl 中。
 *
 * @author Li
 * @since 2026-07-26
 */
@Component
public class StockBillServiceHelper {

    private static final long QTY_DIVISOR = 100L;

    /**
     * 将入库单或出库单主表转换为对应的详情 VO。
     */
    public <T extends StockBillDetailVoMapping.BillTarget> T convertToDetailVo(
            StockBillDetailVoMapping.BillSource bill, Supplier<T> targetFactory) {
        T vo = targetFactory.get();
        vo.setWorkBillId(String.valueOf(bill.getId()));
        vo.setBillNo(bill.getBillNo());
        vo.setSourceType(bill.getSourceType());
        vo.setSourceId(bill.getSourceId() != null ? String.valueOf(bill.getSourceId()) : null);
        vo.setSourceNo(bill.getSourceNo());
        vo.setSourcePartyId(bill.getSourcePartyId() != null ? String.valueOf(bill.getSourcePartyId()) : null);
        vo.setSourcePartyName(bill.getSourcePartyName() != null ? bill.getSourcePartyName() : "");
        vo.setEntryMode(bill.getEntryMode());
        vo.setWarehouseId(bill.getWarehouseId() != null ? String.valueOf(bill.getWarehouseId()) : null);
        vo.setWarehouseName(bill.getWarehouseName());
        vo.setStatus(bill.getStatus());
        vo.setConfirmedById(bill.getConfirmedById() != null ? String.valueOf(bill.getConfirmedById()) : null);
        vo.setConfirmedByName(bill.getConfirmedByName() != null ? bill.getConfirmedByName() : "");
        vo.setConfirmedAt(bill.getConfirmedAt());
        vo.setCreatedById(bill.getCreatedById() != null ? String.valueOf(bill.getCreatedById()) : null);
        vo.setCreatedByName(bill.getCreatedByName() != null ? bill.getCreatedByName() : "");
        vo.setResponsibleById(bill.getResponsibleById() != null ? String.valueOf(bill.getResponsibleById()) : null);
        vo.setResponsibleByName(bill.getResponsibleByName() != null ? bill.getResponsibleByName() : "");
        vo.setVersion(bill.getVersion() != null ? bill.getVersion() : 0);
        vo.setCreateTime(bill.getCreateTime());
        vo.setUpdateTime(bill.getUpdateTime());
        vo.setBillType(bill.getBillType());
        vo.setManualReason(bill.getManualReason() != null ? bill.getManualReason() : "");
        vo.setRemark(bill.getRemark() != null ? bill.getRemark() : "");
        return vo;
    }

    /**
     * 将入库单或出库单明细转换为对应的详情明细 VO。
     */
    public <T extends StockBillDetailVoMapping.BillItemTarget> List<T> convertToDetailItemVos(
            List<? extends StockBillDetailVoMapping.BillItemSource> items, Supplier<T> targetFactory) {
        return items.stream().map(item -> {
            T vo = targetFactory.get();
            vo.setWorkBillItemId(String.valueOf(item.getId()));
            vo.setWorkBillId(String.valueOf(item.getBillId()));
            vo.setBillNo(item.getBillNo());
            vo.setSourceItemId(item.getSourceItemId() != null ? String.valueOf(item.getSourceItemId()) : null);
            vo.setProductId(String.valueOf(item.getProductId()));
            vo.setProductCode(item.getProductCode());
            vo.setProductName(item.getProductName());
            vo.setUnitName(item.getUnitName());
            vo.setQuantityPrecision(item.getQuantityPrecision());
            vo.setPlanQty(QtyUtil.toDecimal(item.getPlanQty()));
            vo.setProcessedQty(QtyUtil.toDecimal(item.getProcessedQty()));
            vo.setPendingQty(QtyUtil.toDecimal(item.getPendingQty()));
            BigDecimal currentQty = QtyUtil.toDecimal(item.getCurrentQty());
            vo.setCurrentQty(currentQty != null ? currentQty : BigDecimal.ZERO);
            vo.setQualifiedQty(QtyUtil.defaultZero(QtyUtil.toDecimal(item.getQualifiedQty())));
            vo.setDefectiveQty(QtyUtil.defaultZero(QtyUtil.toDecimal(item.getDefectiveQty())));
            vo.setStockBillItemId(item.getStockBillItemId() != null ? String.valueOf(item.getStockBillItemId()) : null);
            vo.setCreateTime(item.getCreateTime());
            vo.setUpdateTime(item.getUpdateTime());
            vo.setRemark(item.getRemark());
            return vo;
        }).toList();
    }

    // ==================== 1. 数量摘要构建 ====================

    /**
     * 填充列表项 VO 的数量相关字段（itemCount、totalCurrentQty、quantityUnitName、quantitySummary）。
     *
     * @param directionLabel 方向文案，如 "本次入库" 或 "本次出库"
     * @param items          入库单或出库单明细列表
     * @param target         列表项 VO
     */
    public void populateQuantityFields(String directionLabel,
                                       List<? extends StockBillDetailVoMapping.BillItemSource> items,
                                       StockBillDetailVoMapping.QuantityTarget target) {
        if (items == null || items.isEmpty()) {
            target.setItemCount(0);
            target.setTotalCurrentQty(null);
            target.setQuantityUnitName("");
            target.setQuantitySummary("0");
            return;
        }
        int itemCount = items.size();
        target.setItemCount(itemCount);

        // 判断所有明细是否同一单位
        String firstUnit = items.getFirst().getUnitName();
        boolean sameUnit = items.stream()
                .allMatch(item -> firstUnit != null && firstUnit.equals(item.getUnitName()));

        if (sameUnit) {
            // 同一单位：可求和，显示总量和单位
            long sumRaw = items.stream()
                    .mapToLong(item -> item.getCurrentQty() != null ? item.getCurrentQty() : 0L)
                    .sum();
            target.setTotalCurrentQty((int) (sumRaw / QTY_DIVISOR));
            target.setQuantityUnitName(firstUnit != null ? firstUnit : "");
        } else {
            // 不同单位：总数量无意义，返回 null；quantityUnitName 留空，前端自行用 itemCount 展示
            target.setTotalCurrentQty(null);
            target.setQuantityUnitName("");
        }

        // 构建摘要："本次入库 : 商品A数量单位 , 商品B数量单位"
        StringBuilder sb = new StringBuilder(directionLabel).append(" : ");
        for (int i = 0; i < itemCount; i++) {
            StockBillDetailVoMapping.BillItemSource item = items.get(i);
            long qty = (item.getCurrentQty() != null ? item.getCurrentQty() : 0L) / QTY_DIVISOR;
            String productName = item.getProductName() != null ? item.getProductName() : "";
            String unitName = item.getUnitName() != null ? item.getUnitName() : "";
            sb.append(productName).append(qty);
            if (StrUtil.isNotBlank(unitName)) {
                sb.append(unitName);
            }
            if (i < itemCount - 1) {
                sb.append(" , ");
            }
        }
        target.setQuantitySummary(sb.toString());
    }

    // ==================== 2. 分页汇总统计 ====================

    /**
     * 基于当前分页记录生成入库单或出库单分页汇总。
     *
     * @param records       入库单或出库单列表项
     * @param targetFactory 汇总 VO 工厂
     * @return 汇总结果
     */
    public <T extends StockBillDetailVoMapping.SummaryTarget> T buildSummary(
            List<? extends StockBillDetailVoMapping.SummarySource> records, Supplier<T> targetFactory) {
        int sourceGeneratedCount = 0;
        int pendingCount = 0;
        int confirmedCount = 0;
        int cancelledCount = 0;
        for (StockBillDetailVoMapping.SummarySource record : records) {
            if (EntryMode.SOURCE_GENERATED.name().equals(record.getEntryMode())) {
                sourceGeneratedCount++;
            }
            String status = record.getStatus();
            if (StockBillStatus.PENDING_CONFIRM.name().equals(status)) {
                pendingCount++;
            } else if (StockBillStatus.CONFIRMED.name().equals(status)) {
                confirmedCount++;
            } else if (StockBillStatus.CANCELLED.name().equals(status)) {
                cancelledCount++;
            }
        }
        T summary = targetFactory.get();
        summary.setSourceGeneratedCount(sourceGeneratedCount);
        summary.setPendingCount(pendingCount);
        summary.setConfirmedCount(confirmedCount);
        summary.setCancelledCount(cancelledCount);
        return summary;
    }
}
