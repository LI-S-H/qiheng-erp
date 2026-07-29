package com.qiheng.erp.warehouse.domain.dto;

import com.qiheng.erp.warehouse.domain.enums.OutboundType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/**
 * 新增手工出库单草稿请求
 *
 * @author Li
 * @since 2026-07-28
 */
@Data
@Schema(description = "新增手工出库单草稿请求")
public class OutboundBillCreateDto {

    @Schema(
            description = "出库单类型",
            allowableValues = {"SALES_OUT", "PURCHASE_RETURN", "ADJUST_OUT"}
    )
    @NotNull(message = "出库单类型不能为空")
    private OutboundType billType;

    @Schema(description = "原业务单号；线下补录可以不关联来源单据，选择来源单据时必须与来源单据ID同时传入")
    @Size(max = 64, message = "来源单号最多64个字符")
    private String sourceNo;

    @Schema(description = "来源单据ID；线下补录可以不传，选择已有来源单据时必须与来源单号同时传入")
    private String sourceId;

    @Schema(description = "仓库ID")
    @NotBlank(message = "仓库ID不能为空")
    private String warehouseId;

    @Schema(description = "来源对象ID，销售出库为客户ID，采购退货为供应商ID，调整出库为来源仓库ID")
    @NotBlank(message = "来源对象ID不能为空")
    @Size(max = 64, message = "来源对象ID最多64个字符")
    private String sourcePartyId;

    @Schema(description = "来源对象名称快照，销售出库为客户名称，采购退货为供应商名称，调整出库为来源仓库名称")
    @NotBlank(message = "来源对象名称不能为空")
    @Size(max = 200, message = "来源对象名称最多200个字符")
    private String sourcePartyName;

    @Schema(description = "补录原因或库存调整原因")
    @NotBlank(message = "补录或调整原因不能为空")
    @Size(max = 500, message = "补录或调整原因最多500个字符")
    private String manualReason;

    @Schema(description = "明细列表")
    @NotEmpty(message = "明细列表不能为空")
    @Valid
    private List<OutboundBillItemCreateDto> items;

    @Schema(description = "备注，选填")
    @Size(max = 500, message = "备注最多500个字符")
    private String remark;

    /**
     * 来源单据仅支持完整关联或完全不关联两种状态：线下补录可以没有上游单据，
     * 但一旦选择来源单据，来源 ID 与来源单号必须同时存在，避免主表追溯信息失配。
     */
    @AssertTrue(message = "来源单据ID和来源单号必须同时填写或同时留空")
    @Schema(hidden = true)
    public boolean isSourceReferencePairValid() {
        if (billType == null) {
            return true;
        }
        if (billType == OutboundType.ADJUST_OUT) {
            return (sourceId == null || sourceId.isBlank()) && (sourceNo == null || sourceNo.isBlank());
        }
        boolean hasSourceId = sourceId != null && !sourceId.isBlank();
        boolean hasSourceNo = sourceNo != null && !sourceNo.isBlank();
        return hasSourceId == hasSourceNo;
    }
}
