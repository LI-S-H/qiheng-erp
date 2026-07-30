package com.qiheng.erp.warehouse.domain.outbound.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qiheng.erp.warehouse.domain.support.StockBillDetailVoMapping;
import com.qiheng.erp.warehouse.domain.stockbill.vo.StockBillDetailItemBaseVo;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.util.List;

/**
 * 出库单详情。
 *
 * @author Li
 * @since 2026-07-26
 */
@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "出库单详情")
public class OutboundBillDetailVo extends OutboundBillListItemVo implements StockBillDetailVoMapping.BillTarget {

    @Schema(description = "手工补录或库存调整原因")
    private String manualReason;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "出库单明细列表")
    private List<OutboundBillDetailItemVo> items;

    @Data
    @EqualsAndHashCode(callSuper = true)
    @ToString(callSuper = true)
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "出库单明细项")
    public static class OutboundBillDetailItemVo extends StockBillDetailItemBaseVo {
    }
}
