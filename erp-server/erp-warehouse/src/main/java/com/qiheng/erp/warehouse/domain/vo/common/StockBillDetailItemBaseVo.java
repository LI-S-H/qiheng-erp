package com.qiheng.erp.warehouse.domain.vo.common;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.qiheng.erp.warehouse.domain.support.StockBillDetailVoMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 出入库工作单详情明细公共字段。
 */
@Data
public class StockBillDetailItemBaseVo implements StockBillDetailVoMapping.BillItemTarget {

    @Schema(description = "工作单明细 ID")
    private String workBillItemId;

    @Schema(description = "工作单 ID")
    private String workBillId;

    @Schema(description = "工作单号")
    private String billNo;

    @Schema(description = "来源单据明细 ID")
    private String sourceItemId;

    @Schema(description = "产品 ID")
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

    @Schema(description = "生成本单前累计已处理数量")
    private BigDecimal processedQty;

    @Schema(description = "确认本单后剩余未处理数量")
    private BigDecimal pendingQty;

    @Schema(description = "本次数量")
    private BigDecimal currentQty;

    @Schema(description = "合格数量")
    private BigDecimal qualifiedQty;

    @Schema(description = "不合格数量")
    private BigDecimal defectiveQty;

    @Schema(description = "确认后生成的库存流水明细 ID")
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
