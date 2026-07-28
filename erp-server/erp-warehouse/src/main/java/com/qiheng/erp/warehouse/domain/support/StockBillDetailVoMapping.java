package com.qiheng.erp.warehouse.domain.support;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出入库单详情 VO 转换的公共契约。
 *
 * <p>通过显式接口保持实体、VO 与转换逻辑之间的编译期约束，避免反射复制导致字段遗漏或类型不匹配。</p>
 */
public final class StockBillDetailVoMapping {

    private StockBillDetailVoMapping() {
    }

    /**
     * 出入库单详情 VO 转换的公共契约。
     */
    public interface BillSource {
        Long getId();
        String getBillNo();
        String getSourceType();
        Long getSourceId();
        String getSourceNo();
        Long getSourcePartyId();
        String getSourcePartyName();
        String getEntryMode();
        Long getWarehouseId();
        String getWarehouseName();
        String getStatus();
        Long getConfirmedById();
        String getConfirmedByName();
        LocalDateTime getConfirmedAt();
        Long getCreatedById();
        String getCreatedByName();
        Long getResponsibleById();
        String getResponsibleByName();
        Integer getVersion();
        LocalDateTime getCreateTime();
        LocalDateTime getUpdateTime();
        String getBillType();
        String getManualReason();
        String getRemark();
    }

    public interface BillTarget {
        void setWorkBillId(String workBillId);
        void setBillNo(String billNo);
        void setSourceType(String sourceType);
        void setSourceId(String sourceId);
        void setSourceNo(String sourceNo);
        void setSourcePartyId(String sourcePartyId);
        void setSourcePartyName(String sourcePartyName);
        void setEntryMode(String entryMode);
        void setWarehouseId(String warehouseId);
        void setWarehouseName(String warehouseName);
        void setStatus(String status);
        void setConfirmedById(String confirmedById);
        void setConfirmedByName(String confirmedByName);
        void setConfirmedAt(LocalDateTime confirmedAt);
        void setCreatedById(String createdById);
        void setCreatedByName(String createdByName);
        void setResponsibleById(String responsibleById);
        void setResponsibleByName(String responsibleByName);
        void setVersion(Integer version);
        void setCreateTime(LocalDateTime createTime);
        void setUpdateTime(LocalDateTime updateTime);
        void setBillType(String billType);
        void setManualReason(String manualReason);
        void setRemark(String remark);
    }

    public interface QuantityTarget {
        void setItemCount(Integer itemCount);
        void setTotalCurrentQty(Integer totalCurrentQty);
        void setQuantityUnitName(String quantityUnitName);
        void setQuantitySummary(String quantitySummary);
    }

    public interface SummarySource {
        String getEntryMode();
        String getStatus();
    }

    public interface SummaryTarget {
        void setSourceGeneratedCount(int sourceGeneratedCount);
        void setPendingCount(int pendingCount);
        void setConfirmedCount(int confirmedCount);
        void setCancelledCount(int cancelledCount);
    }

    public interface BillItemSource {
        Long getId();
        Long getBillId();
        String getBillNo();
        Long getSourceItemId();
        Long getProductId();
        String getProductCode();
        String getProductName();
        String getUnitName();
        Integer getQuantityPrecision();
        Long getPlanQty();
        Long getProcessedQty();
        Long getPendingQty();
        Long getCurrentQty();
        Long getQualifiedQty();
        Long getDefectiveQty();
        Long getStockBillItemId();
        LocalDateTime getCreateTime();
        LocalDateTime getUpdateTime();
        String getRemark();
    }

    public interface BillItemTarget {
        void setWorkBillItemId(String workBillItemId);
        void setWorkBillId(String workBillId);
        void setBillNo(String billNo);
        void setSourceItemId(String sourceItemId);
        void setProductId(String productId);
        void setProductCode(String productCode);
        void setProductName(String productName);
        void setUnitName(String unitName);
        void setQuantityPrecision(Integer quantityPrecision);
        void setPlanQty(BigDecimal planQty);
        void setProcessedQty(BigDecimal processedQty);
        void setPendingQty(BigDecimal pendingQty);
        void setCurrentQty(BigDecimal currentQty);
        void setQualifiedQty(BigDecimal qualifiedQty);
        void setDefectiveQty(BigDecimal defectiveQty);
        void setStockBillItemId(String stockBillItemId);
        void setCreateTime(LocalDateTime createTime);
        void setUpdateTime(LocalDateTime updateTime);
        void setRemark(String remark);
    }
}
