package com.qiheng.erp.returnorder.domain.vo;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.qiheng.erp.common.config.MoneyStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 退货单明细及服务端派生的可退数量。 */
@Data
@Schema(description = "退货单明细响应")
public class ReturnOrderItemVo {
    @Schema(description = "退货单明细ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long returnOrderItemId;

    @Schema(description = "退货单主表ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long returnOrderId;

    @Schema(description = "来源订单明细ID（如采购订单明细ID）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceOrderItemId;

    @Schema(description = "产品ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    @Schema(description = "产品编码")
    private String productCode;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "单位名称")
    private String unitName;

    @Schema(description = "数量小数位精度")
    private Integer quantityPrecision;

    @Schema(description = "来源单已履约数量（如采购已入库数量）")
    private BigDecimal sourceFulfilledQty;

    @Schema(description = "当前仓库可用库存数量，来源可退明细查询时返回")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal stockAvailableQty;

    @Schema(description = "同一来源明细已被其他有效退货单占用的数量，来源可退明细查询时返回")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal occupiedQty;

    @Schema(description = "剩余可退数量 = min(来源已履约数, 仓库可用库存) - 已占用数，仅新增/编辑来源时返回")
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private BigDecimal availableReturnQty;

    @Schema(description = "申请退货数量")
    private BigDecimal requestedQty;

    @Schema(description = "审核通过的退货数量")
    private BigDecimal approvedQty;

    @Schema(description = "已实际出库执行的退货数量")
    private BigDecimal processedQty;

    @Schema(description = "单价，按100倍整数存储的放大值")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal unitPrice;

    @Schema(description = "明细总金额")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal totalAmount;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    private LocalDateTime updateTime;
}
