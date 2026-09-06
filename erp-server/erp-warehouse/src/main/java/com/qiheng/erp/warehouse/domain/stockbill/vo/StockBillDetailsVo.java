package com.qiheng.erp.warehouse.domain.stockbill.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(description = "库存流水详情")
public class StockBillDetailsVo extends StockBillListItemVo {

    @Schema(description = "库存流水明细列表")
    private List<StockBillDetailItemVo> items;

    @Data
    @Schema(description = "库存流水明细项")
    public static class StockBillDetailItemVo {

        @Schema(description = "库存流水明细ID")
        private Long stockLedgerItemId;

        @Schema(description = "库存流水ID")
        private Long stockLedgerId;

        @Schema(description = "产品ID")
        private Long productId;

        @Schema(description = "产品编码")
        private String productCode;

        @Schema(description = "产品名称")
        private String productName;

        @Schema(description = "单位")
        private String unitName;

        @Schema(description = "数量小数位：0-2，确认入出库时固化的产品精度快照，用于前端按产品精度渲染数量")
        private Integer quantityPrecision;

        @Schema(description = "操作前数量")
        private Double beforeQty;

        @Schema(description = "合格数量；采购入库、销售退货按实际记录返回，其他出入库类型返回 0")
        private Double qualifiedQty;

        @Schema(description = "不合格数量；采购入库、销售退货按实际记录返回，其他出入库类型返回 0")
        private Double defectiveQty;

        @Schema(description = "操作数量")
        private Double changeQty;

        @Schema(description = "操作后数量")
        private Double afterQty;

        @Schema(description = "备注")
        private String remark;
    }
}