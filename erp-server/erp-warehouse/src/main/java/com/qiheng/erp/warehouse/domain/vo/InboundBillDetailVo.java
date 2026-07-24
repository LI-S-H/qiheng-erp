package com.qiheng.erp.warehouse.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@JsonInclude(JsonInclude.Include.ALWAYS)
@Schema(description = "入库单详情")
public class InboundBillDetailVo extends InboundBillListItemVo {

    @Schema(description = "手工补录或库存调整原因")
    private String manualReason;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "入库单明细列表")
    private List<InboundBillDetailItemVo> items;

    @Data
    @JsonInclude(JsonInclude.Include.ALWAYS)
    @Schema(description = "入库单明细项")
    public static class InboundBillDetailItemVo {

        @Schema(description = "入库单明细ID")
        private String workBillItemId;

        @Schema(description = "入库单ID")
        private String workBillId;

        @Schema(description = "入库单号")
        private String billNo;

        @Schema(description = "来源单据明细ID")
        private String sourceItemId;

        @Schema(description = "产品ID")
        private String productId;

        @Schema(description = "产品编码")
        private String productCode;

        @Schema(description = "产品名称")
        private String productName;

        @Schema(description = "单位")
        private String unitName;

        @Schema(description = "数量小数位")
        private Integer quantityPrecision;

        @Schema(description = "来源计划数量")
        private BigDecimal planQty;

        @Schema(description = "生成本单前累计已入库数量")
        private BigDecimal processedQty;

        @Schema(description = "确认本单后剩余未入库数量")
        private BigDecimal pendingQty;

        @Schema(description = "本次入库数量")
        private BigDecimal currentQty;

        @Schema(description = "合格数量")
        private BigDecimal qualifiedQty;

        @Schema(description = "不合格数量")
        private BigDecimal defectiveQty;

        @Schema(description = "操作前数量（采购入库、销售退货入库无，固定 0）")
        private BigDecimal beforeQty;

        @Schema(description = "操作数量（等于本次入库数量）")
        private BigDecimal changeQty;

        @Schema(description = "操作后数量（采购入库、销售退货入库无，固定等于 changeQty）")
        private BigDecimal afterQty;

        @Schema(description = "确认后生成的库存流水明细ID")
        private String stockBillItemId;

        @Schema(description = "创建时间")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime createTime;

        @Schema(description = "更新时间")
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime updateTime;

        @Schema(description = "备注")
        private String remark;
    }
}