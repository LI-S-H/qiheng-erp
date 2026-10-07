package com.qiheng.erp.product.domain.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 产品参考采购价调整请求。
 *
 * <p>必须独立于产品编辑接口,避免编辑与价格变更混在同一事务内导致重算副作用。
 * 价格变化时在同一事务内调用 SupplierScoreRecalculateService.recalcForProductReferencePriceChange
 * 重算所有供应该产品的有效供货关系的价格分、推荐分及对应供应商汇总分;
 * 重算失败则参考价变更整体回滚。</p>
 *
 * <p>对应接口文档的 {@code ProductReferencePriceRequest}。</p>
 *
 * @author Li
 * @since 2026-09-25
 */
@Data
@Schema(description = "产品参考采购价调整请求")
public class ProductReferencePriceDto {

    @Schema(description = "新参考采购价，最多两位小数")
    @NotNull(message = "参考采购价不能为空")
    @DecimalMin(value = "0", message = "参考采购价不能为负数")
    @Digits(integer = 16, fraction = 2, message = "参考采购价最多保留两位小数")
    private BigDecimal referencePurchasePrice;
}
