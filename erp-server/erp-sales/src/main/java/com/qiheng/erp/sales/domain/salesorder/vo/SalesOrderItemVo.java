package com.qiheng.erp.sales.domain.salesorder.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * <p>
 * 销售订单明细响应 VO
 * </p>
 *
 * @author Li
 * @since 2026-08-11
 */
@Data
@Schema(description = "销售订单明细响应")
public class SalesOrderItemVo {

    @Schema(description = "明细ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long salesOrderItemId;

    @Schema(description = "销售订单ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long salesOrderId;

    @Schema(description = "销售单号")
    private String salesNo;

    @Schema(description = "产品ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "单位名称")
    private String unitName;

    @Schema(description = "数量小数位快照：0-2")
    private Integer quantityPrecision;

    @Schema(description = "销售数量，按 100 倍存储值还原为业务小数")
    private BigDecimal quantity;

    @Schema(description = "已锁定库存数量，按 100 倍存储值还原为业务小数")
    private BigDecimal lockedQty;

    @Schema(description = "已出库数量，按 100 倍存储值还原为业务小数")
    private BigDecimal outboundQty;

    @Schema(description = "销售单价，按 100 倍存储值还原为业务小数")
    private BigDecimal unitPrice;

    @Schema(description = "明细金额，按 100 倍存储值还原为业务小数")
    private BigDecimal totalAmount;

    @Schema(description = "备注")
    private String remark;
}