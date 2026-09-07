package com.qiheng.erp.dashboard.domain.topproduct.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.qiheng.erp.dashboard.config.DashboardNonNegativeMoneySerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * <p>
 * 工作台销售商品排行 VO
 * </p>
 *
 * <p>近 30 日销售订单明细按 product 聚合，sales_amount ×100 还原为业务小数。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@Schema(description = "工作台销售商品排行")
public class DashboardTopProductVO {

    @Schema(description = "产品 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "近 30 日销售金额（人民币元，最多两位小数非负金额字符串）")
    @JsonSerialize(using = DashboardNonNegativeMoneySerializer.class)
    private BigDecimal salesAmount;

    @Schema(description = "近 30 日销售数量，按 100 倍存储值还原为业务小数")
    private BigDecimal salesQty;

    @Schema(description = "当前可用库存数量，按 100 倍存储值还原为业务小数")
    private BigDecimal availableQty;
}