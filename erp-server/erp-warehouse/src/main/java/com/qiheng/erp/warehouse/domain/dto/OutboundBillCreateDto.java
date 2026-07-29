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

    @Schema(description = "原业务单号；非调整类型必须与来源单据ID同时传入，调整类型传空字符串")
    @Size(max = 64, message = "来源单号最多64个字符")
    private String sourceNo;

    @Schema(description = "来源单据ID；非调整类型必须从已有单据选择并与来源单号同时传入，调整类型不传")
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
     * 非调整类型必须选择来源单据，避免直接调用接口时绕过前端的来源单选择限制。
     */
    @AssertTrue(message = "非调整类型时必须选择来源单据")
    @Schema(hidden = true)
    public boolean isSourceReferenceRequired() {
        if (billType == null) {
            return true;
        }
        if (billType == OutboundType.ADJUST_OUT) {
            return true;
        }
        return sourceId != null && !sourceId.isBlank()
                && sourceNo != null && !sourceNo.isBlank();
    }
}
