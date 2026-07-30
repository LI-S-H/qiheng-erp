package com.qiheng.erp.warehouse.domain.outbound.dto;

import com.qiheng.erp.warehouse.domain.support.StockBillDraftItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 出库单明细创建请求
 *
 * @author Li
 * @since 2026-07-28
 */
@Data
@Schema(description = "出库单明细创建请求")
public class OutboundBillItemCreateDto implements StockBillDraftItem {

    @Schema(description = "产品ID")
    @NotBlank(message = "产品ID不能为空")
    private String productId;

    @Schema(description = "来源单据明细ID，前端从已有单据下拉选择时自动附带")
    private String sourceItemId;

    @Schema(description = "来源计划数量，业务真实值（如100件存100），前端从来源单据带入；后端按100倍整数持久化")
    private BigDecimal planQty;

    @Schema(description = "本次出库数量，最多两位小数")
    @NotNull(message = "本次出库数量不能为空")
    @DecimalMin(value = "0.01", message = "本次出库数量必须大于0")
    private BigDecimal currentQty;

    @Schema(description = "合格数量，采购退货出库使用，其他类型传0")
    @NotNull(message = "合格数量不能为空")
    @DecimalMin(value = "0", message = "合格数量不能为负")
    private BigDecimal qualifiedQty;

    @Schema(description = "不合格数量，采购退货出库使用，其他类型传0")
    @NotNull(message = "不合格数量不能为空")
    @DecimalMin(value = "0", message = "不合格数量不能为负")
    private BigDecimal defectiveQty;

    @Schema(description = "备注，选填")
    @Size(max = 500, message = "备注最多500个字符")
    private String remark;
}
