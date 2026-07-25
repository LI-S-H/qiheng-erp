package com.qiheng.erp.warehouse.domain.dto;

import com.qiheng.erp.warehouse.domain.enums.InboundType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/**
 * 新增手工入库单草稿请求
 *
 * @author Li
 * @since 2026-07-25
 */
@Data
@Schema(description = "新增手工入库单草稿请求")
public class InboundBillCreateDto {

    @Schema(
            description = "入库单类型",
            allowableValues = {"PURCHASE_IN", "SALES_RETURN", "ADJUST_IN"}
    )
    @NotNull(message = "入库单类型不能为空")
    private InboundType billType;

    @Schema(description = "原业务单号，非调整类型必填；调整类型传空字符串")
    @Size(max = 64, message = "来源单号最多64个字符")
    private String sourceNo;

    @Schema(description = "来源单据ID，前端从已有单据下拉选择时附带；手动输入模式或调整类型不传")
    private String sourceId;

    @Schema(description = "仓库ID")
    @NotBlank(message = "仓库ID不能为空")
    private String warehouseId;

    @Schema(description = "来源对象ID，采购入库为供应商ID，销售退货为客户ID")
    @Size(max = 64, message = "来源对象ID最多64个字符")
    private String sourcePartyId;

    @Schema(description = "来源对象名称快照，采购入库为供应商名称，销售退货为客户名称")
    @Size(max = 200, message = "来源对象名称最多200个字符")
    private String sourcePartyName;

    @Schema(description = "补录原因或库存调整原因")
    @NotBlank(message = "补录或调整原因不能为空")
    @Size(max = 500, message = "补录或调整原因最多500个字符")
    private String manualReason;

    @Schema(description = "明细列表")
    @NotEmpty(message = "明细列表不能为空")
    @Valid
    private List<InboundBillItemCreateDto> items;

    @Schema(description = "备注")
    @Size(max = 500, message = "备注最多500个字符")
    private String remark;

    /**
     * 非调整类型时来源单号不能为空
     */
    @AssertTrue(message = "非调整类型时原业务单号不能为空")
    @Schema(hidden = true)
    public boolean isSourceNoRequired() {
        if (billType == null) {
            return true;
        }
        if (billType == InboundType.ADJUST_IN) {
            return true;
        }
        return sourceNo != null && !sourceNo.isBlank();
    }
}
