package com.qiheng.erp.returnorder.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 可退来源详情：来源单表头与可退明细使用同一套计算口径，供确认页和 AI 预检使用。
 *
 * @author Li
 * @since 2026-11-24
 */
@Data
@Schema(description = "可退来源订单详情")
public class ReturnableSourceDetailVo {

    @Schema(description = "退货方向：SALES_RETURN / PURCHASE_RETURN")
    private String returnType;

    @Schema(description = "来源订单ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceOrderId;

    @Schema(description = "来源订单编号")
    private String sourceOrderNo;

    @Schema(description = "来源订单状态编码")
    private String sourceOrderStatus;

    @Schema(description = "往来单位ID（供应商ID/客户ID）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long partyId;

    @Schema(description = "往来单位编码")
    private String partyCode;

    @Schema(description = "往来单位名称")
    private String partyName;

    @Schema(description = "仓库ID；退货仓库必须与来源单仓库一致")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long warehouseId;

    @Schema(description = "仓库名称")
    private String warehouseName;

    @Schema(description = "可退明细")
    private List<ReturnOrderItemVo> items;
}