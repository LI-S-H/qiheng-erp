package com.qiheng.erp.warehouse.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 编辑出入库单草稿或待确认单请求
 *
 * @author Li
 * @since 2026-07-25
 */
@Data
@Schema(description = "编辑出入库单草稿或待确认单请求")
public class StockBillItemUpdateDto {

    @Schema(description = "乐观锁版本号")
    @NotNull(message = "版本号不能为空")
    @Min(value = 0, message = "版本号不能为负")
    private Integer version;

    @Schema(description = "仓库ID，仅DRAFT状态允许修改")
    private String warehouseId;

    @Schema(description = "来源单号，仅人工补录DRAFT可修改")
    @Size(max = 64, message = "来源单号最多64个字符")
    private String sourceNo;

    @Schema(description = "来源对象ID，DRAFT可修改")
    @Size(max = 64, message = "来源对象ID最多64个字符")
    private String sourcePartyId;

    @Schema(description = "来源对象名称，DRAFT可修改")
    @Size(max = 200, message = "来源对象名称最多200个字符")
    private String sourcePartyName;

    @Schema(description = "补录原因或库存调整原因，手工草稿必填")
    @Size(max = 500, message = "补录或调整原因最多500个字符")
    private String manualReason;

    @Schema(description = "明细列表，至少1条")
    @NotEmpty(message = "明细列表不能为空")
    @Valid
    private List<StockBillUpdateDto> items;

    @Schema(description = "备注，选填")
    @Size(max = 500, message = "备注最多500个字符")
    private String remark;
}
