package com.qiheng.erp.warehouse.domain.support;

/**
 * 出入库单编辑时需要访问的主表公共字段契约。
 *
 * <p>入库单和出库单在编辑草稿、待确认单时遵循同一套字段权限规则，
 * 但仍分别映射到各自的数据表。通过该契约复用编辑规则，避免让支持类
 * 依赖某一个具体单据实体。</p>
 */
public final class StockBillEditMapping {

    private StockBillEditMapping() {
    }

    /**
     * 可编辑出入库单主表的共同读写能力。
     *
     * @param <T> 具体单据实体类型，保留链式 Setter 的返回类型
     */
    public interface EditableBill<T extends EditableBill<T>> {

        String getEntryMode();

        Long getWarehouseId();

        T setWarehouseId(Long warehouseId);

        T setWarehouseName(String warehouseName);

        Long getSourceId();

        T setSourceId(Long sourceId);

        Long getSourcePartyId();

        String getSourcePartyName();

        T setSourcePartyId(Long sourcePartyId);

        T setSourcePartyName(String sourcePartyName);

        String getSourceNo();

        T setSourceNo(String sourceNo);

        String getManualReason();

        T setManualReason(String manualReason);

        T setRemark(String remark);
    }
}
