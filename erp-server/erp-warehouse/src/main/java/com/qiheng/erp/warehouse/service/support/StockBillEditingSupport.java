package com.qiheng.erp.warehouse.service.support;

import cn.hutool.core.util.StrUtil;
import com.qiheng.erp.common.exception.BizException;
import com.qiheng.erp.common.exception.ErrorCode;
import com.qiheng.erp.common.util.QtyUtil;
import com.qiheng.erp.common.util.IdUtil;
import com.qiheng.erp.warehouse.domain.stockbill.dto.StockBillItemUpdateDto;
import com.qiheng.erp.warehouse.domain.stockbill.dto.StockBillUpdateDto;
import com.qiheng.erp.warehouse.domain.warehouse.entity.Warehouse;
import com.qiheng.erp.warehouse.domain.common.enums.EntryMode;
import com.qiheng.erp.warehouse.domain.stockbill.enums.StockBillStatus;
import com.qiheng.erp.warehouse.domain.support.StockBillEditMapping;
import com.qiheng.erp.warehouse.mapper.WarehouseMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Comparator;
import java.math.BigDecimal;

/**
 * 出入库单编辑草稿或待确认单的公共业务支持。
 *
 * <p>统一处理编辑状态、乐观锁、主表字段权限和待确认单明细结构限制。
 * 明细的实际删除、重建与持久化仍由入库/出库 Service 负责，保留两类
 * 明细实体及其外键字段的业务语义。</p>
 */
@Component
public class StockBillEditingSupport {

    @Autowired
    private WarehouseMapper warehouseMapper;

    /**
     * 校验单据是否允许编辑，并校验客户端提交的乐观锁版本。
     *
     * @return 当前编辑阶段，用于决定主表和明细可修改范围
     */
    public EditStage validateEditableStage(String status, Integer persistedVersion, Integer requestVersion) {
        EditStage stage = EditStage.fromStatus(status);
        if (stage == null) {
            throw new BizException(ErrorCode.BILL_STATUS_INVALID.getCode(), "仅草稿和待确认单可编辑");
        }
        validateVersion(persistedVersion, requestVersion);
        return stage;
    }

    /**
     * 校验单据当前状态是否允许执行业务操作，并校验乐观锁版本。
     *
     * <p>状态变更本身仍由调用方在完成自身业务处理后执行，避免公共类介入
     * 入库、出库各自的实体持久化流程。</p>
     */
    public void validateStatusAndVersion(String status, Integer persistedVersion, Integer requestVersion,
                                         String invalidStatusMessage, StockBillStatus... allowedStatuses) {
        // 校验状态是否在允许范围内
        for (StockBillStatus allowedStatus : allowedStatuses) {
            if (allowedStatus.name().equals(status)) {
                validateVersion(persistedVersion, requestVersion);
                return;
            }
        }
        throw new BizException(ErrorCode.BILL_STATUS_INVALID.getCode(), invalidStatusMessage);
    }

    /**
     * 解析工作单 ID，并将非数字输入转换为参数错误，避免泄露底层转换异常。
     */
    public Long parseBillId(String billId, String billName) {
        return IdUtil.parseRequiredLongId(billId, billName + "ID");
    }

    /**
     * 按草稿单的字段权限更新主表。
     *
     * <p>系统生成的单据保持来源信息不变；人工补录可修改来源对象和来源单号；
     * 人工调整可修改来源仓库，但不能修改调整单号。</p>
     */
    public <T extends StockBillEditMapping.EditableBill<T>> void applyDraftFields(
            T bill, StockBillItemUpdateDto dto) {
        updateWarehouseIfChanged(bill, dto.getWarehouseId());
        // 人工补录单允许修正来源对象和来源单号；系统生成单的来源始终由来源业务维护。
        EntryMode entryMode = EntryMode.valueOf(bill.getEntryMode());
        if (entryMode == EntryMode.MANUAL_SUPPLEMENT) {
            // 线下补录可以没有来源单；重新选择来源时，来源 ID 与来源单号必须成对更新或同时清空。
            updateSourceParty(bill, dto);
            updateSourceReference(bill, dto);
        }
        // 人工调整可修改来源仓库，但不能修改调整单号
        else if (entryMode == EntryMode.MANUAL_ADJUSTMENT) {
            // 修改来源仓库
            updateSourceParty(bill, dto);
        }
        // 除了系统生成单，其他状态可修改人工原因
        if (entryMode != EntryMode.SOURCE_GENERATED && dto.getManualReason() != null) {
            bill.setManualReason(dto.getManualReason());
        }
        // 全部状态可修改备注
        if (dto.getRemark() != null) {
            bill.setRemark(dto.getRemark());
        }
        validateRequiredManualFields(bill, entryMode);
    }

    /**
     * 待确认单主表只允许修改备注；明细数量是否可改由调用方继续按结构规则处理。
     */
    public <T extends StockBillEditMapping.EditableBill<T>> void applyPendingConfirmFields(
            T bill, StockBillItemUpdateDto dto) {
        if (dto.getRemark() != null) {
            bill.setRemark(dto.getRemark());
        }
    }

    /**
     * 提交前以数据库当前单头为准复核人工单必填项和来源关联，防止历史数据或绕过前端的请求进入待确认状态。
     */
    public <T extends StockBillEditMapping.EditableBill<T>> void validateBeforeSubmit(T bill) {
        EntryMode entryMode = EntryMode.valueOf(bill.getEntryMode());
        boolean hasSourceId = bill.getSourceId() != null;
        boolean hasSourceNo = StrUtil.isNotBlank(bill.getSourceNo());
        if (entryMode == EntryMode.MANUAL_SUPPLEMENT && hasSourceId != hasSourceNo) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "来源单据ID和来源单号必须同时填写或同时留空");
        }
        if (entryMode == EntryMode.MANUAL_ADJUSTMENT && hasSourceId) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "库存调整单不能关联来源单据ID");
        }
        validateRequiredManualFields(bill, entryMode);
    }

    /**
     * 校验待确认单的明细结构未被改变：不能新增、删除或替换产品。
     */
    public void validatePendingConfirmStructure(List<PendingConfirmItemSnapshot> existingItems,
                                                List<StockBillUpdateDto> itemDtos) {
        if (itemDtos.size() != existingItems.size()) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "待确认状态不允许增删明细");
        }
        List<PendingConfirmItemSnapshot> requestItems = itemDtos.stream()
                .map(this::toPendingConfirmItemSnapshot)
                .sorted(pendingConfirmItemComparator())
                .toList();
        List<PendingConfirmItemSnapshot> persistedItems = existingItems.stream()
                .sorted(pendingConfirmItemComparator())
                .toList();
        if (!persistedItems.equals(requestItems)) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(),
                    "待确认状态不允许变更产品、来源明细或计划数量");
        }
    }

    /**
     * 更新待确认单的仓库信息，若仓库ID未改变则不执行更新。
     */
    private <T extends StockBillEditMapping.EditableBill<T>> void updateWarehouseIfChanged(
            T bill, String warehouseIdValue) {
        if (StrUtil.isBlank(warehouseIdValue)) {
            return;
        }
        Long warehouseId = parseRequiredId(warehouseIdValue, "仓库ID");
        if (warehouseId.equals(bill.getWarehouseId())) {
            return;
        }
        Warehouse warehouse = warehouseMapper.selectById(warehouseId);
        if (warehouse == null || warehouse.getStatus() == null || warehouse.getStatus() != 1) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "仓库不存在或已禁用");
        }
        bill.setWarehouseId(warehouseId).setWarehouseName(warehouse.getWarehouseName());
    }

    /**
     * 更新待确认单的来源对象信息，若来源对象ID未改变则不执行更新。
     */
    private <T extends StockBillEditMapping.EditableBill<T>> void updateSourceParty(
            T bill, StockBillItemUpdateDto dto) {
        if (dto.getSourcePartyId() != null) {
            bill.setSourcePartyId(parseNullableId(dto.getSourcePartyId(), "来源对象ID"));
        }
        if (dto.getSourcePartyName() != null) {
            bill.setSourcePartyName(StrUtil.blankToDefault(dto.getSourcePartyName(), null));
        }
    }

    /**
     * 更新人工补录单的来源单据关联。DTO 中未传字段表示不修改，空字符串表示显式清除关联。
     */
    private <T extends StockBillEditMapping.EditableBill<T>> void updateSourceReference(
            T bill, StockBillItemUpdateDto dto) {
        if (dto.getSourceId() == null && dto.getSourceNo() == null) {
            return;
        }
        boolean hasSourceId = StrUtil.isNotBlank(dto.getSourceId());
        boolean hasSourceNo = StrUtil.isNotBlank(dto.getSourceNo());
        if (hasSourceId != hasSourceNo) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "来源单据ID和来源单号必须同时填写或同时留空");
        }
        bill.setSourceId(hasSourceId ? parseNullableId(dto.getSourceId(), "来源单据ID") : null);
        bill.setSourceNo(hasSourceNo ? dto.getSourceNo().trim() : null);
    }

    /**
     * 校验待确认单的必填字段未被改变：来源对象、补录或调整原因。
     */
    private <T extends StockBillEditMapping.EditableBill<T>> void validateRequiredManualFields(
            T bill, EntryMode entryMode) {
        if (entryMode == EntryMode.SOURCE_GENERATED) {
            return;
        }
        if (bill.getSourcePartyId() == null || StrUtil.isBlank(bill.getSourcePartyName())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "来源对象不能为空");
        }
        if (StrUtil.isBlank(bill.getManualReason())) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "补录或调整原因不能为空");
        }
    }

    /**
     * 转换待确认单明细DTO为待确认单明细实体。
     */
    private PendingConfirmItemSnapshot toPendingConfirmItemSnapshot(StockBillUpdateDto dto) {
        return new PendingConfirmItemSnapshot(
                parseRequiredId(dto.getProductId(), "产品ID"),
                parseNullableId(dto.getSourceItemId(), "来源明细ID"),
                toStoredPlanQty(dto.getPlanQty()));
    }

    /**
     * 转换计划数量为存储数量。
     */
    private Long toStoredPlanQty(BigDecimal planQty) {
        if (planQty == null || planQty.compareTo(BigDecimal.ZERO) == 0) {
            return null;
        }
        if (planQty.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "来源计划数量不能为负数");
        }
        return QtyUtil.toStored(planQty);
    }

    /**
     * 待确认单明细比较器，用于排序。
     */
    private Comparator<PendingConfirmItemSnapshot> pendingConfirmItemComparator() {
        return Comparator.comparing(PendingConfirmItemSnapshot::productId)
                .thenComparing(PendingConfirmItemSnapshot::sourceItemId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(PendingConfirmItemSnapshot::planQty,
                        Comparator.nullsFirst(Comparator.naturalOrder()));
    }

    /**
     * 解析必填ID字符串为Long类型。
     */
    private Long parseRequiredId(String value, String fieldName) {
        return IdUtil.parseRequiredLongId(value, fieldName);
    }

    /**
     * 解析可空ID字符串为Long类型。
     */
    private Long parseNullableId(String value, String fieldName) {
        return IdUtil.parseOptionalLongId(value, fieldName);
    }

    /**
     * 验证版本号是否匹配。
     */
    private void validateVersion(Integer persistedVersion, Integer requestVersion) {
        if (!Objects.equals(persistedVersion, requestVersion)) {
            throw new BizException(ErrorCode.STATUS_INVALID.getCode(), "数据已被其他人修改，请刷新后重试");
        }
    }

    /**
     * 可编辑单据的阶段。DRAFT 与 PENDING_CONFIRM 的字段权限不同。
     */
    public enum EditStage {
        DRAFT,
        PENDING_CONFIRM;

        private static EditStage fromStatus(String status) {
            if (StockBillStatus.DRAFT.name().equals(status)) {
                return DRAFT;
            }
            if (StockBillStatus.PENDING_CONFIRM.name().equals(status)) {
                return PENDING_CONFIRM;
            }
            return null;
        }
    }

    /**
     * 待确认单中不得被修改的明细结构字段快照。
     */
    public record PendingConfirmItemSnapshot(Long productId, Long sourceItemId, Long planQty) {
    }
}
