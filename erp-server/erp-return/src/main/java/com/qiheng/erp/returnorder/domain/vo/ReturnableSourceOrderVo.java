package com.qiheng.erp.returnorder.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 可作为退货来源的订单摘要。
 *
 * <p>{@code fulfilledItemCount} 与 {@code totalAvailableReturnQty} 均由服务端根据来源履约数据和
 * 已占用退货数量计算，前端仅展示，不参与二次计算。</p>
 */
@Data
@Schema(description = "可退货来源订单摘要响应")
public class ReturnableSourceOrderVo {
    @Schema(description = "来源订单ID（如采购订单ID）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceOrderId;

    @Schema(description = "来源订单编号")
    private String sourceOrderNo;

    @Schema(description = "往来单位ID（供应商ID/客户ID）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long partyId;

    @Schema(description = "往来单位编码")
    private String partyCode;

    @Schema(description = "往来单位名称")
    private String partyName;

    @Schema(description = "仓库ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long warehouseId;

    @Schema(description = "仓库名称")
    private String warehouseName;

    @Schema(description = "仍有剩余可退数量的明细条数")
    private Integer fulfilledItemCount;

    @Schema(description = "该来源订单下全部明细剩余可退数量合计")
    private BigDecimal totalAvailableReturnQty;
}