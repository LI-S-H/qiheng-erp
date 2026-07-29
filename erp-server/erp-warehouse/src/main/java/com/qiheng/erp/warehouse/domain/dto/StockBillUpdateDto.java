package com.qiheng.erp.warehouse.domain.dto;

import com.qiheng.erp.warehouse.domain.support.StockBillDraftItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 出入库单明细更新请求
 *
 * @author Li
 * @since 2026-07-25
 */
@Data
@Schema(description = "出入库单明细更新请求，前端提交全量明细快照，后端全量替换")
public class StockBillUpdateDto implements StockBillDraftItem {

    @Schema(description = "来源单据明细ID")
    private String sourceItemId;

    @Schema(description = "产品ID")
    @NotBlank(message = "产品ID不能为空")
    private String productId;

    @Schema(description = "来源计划数量，业务真实值，前端从来源单据带入；后端按100倍整数持久化")
    private BigDecimal planQty;

    @Schema(description = "本次入库数量，最多两位小数")
    @NotNull(message = "本次入库数量不能为空")
    @DecimalMin(value = "0.01", message = "本次入库数量必须大于0")
    private BigDecimal currentQty;

    @Schema(description = "合格数量，其他类型传0")
    @NotNull(message = "合格数量不能为空")
    @DecimalMin(value = "0", message = "合格数量不能为负")
    private BigDecimal qualifiedQty;

    @Schema(description = "不合格数量，其他类型传0")
    @NotNull(message = "不合格数量不能为空")
    @DecimalMin(value = "0", message = "不合格数量不能为负")
    private BigDecimal defectiveQty;

    @Schema(description = "备注，选填")
    @Size(max = 500, message = "备注最多500个字符")
    private String remark;
}
