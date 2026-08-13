package com.qiheng.erp.purchase.domain.purchaseorder.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.qiheng.erp.common.config.MoneyStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * <p>
 * 采购订单列表 VO
 * </p>
 *
 * @author Li
 * @since 2026-07-31
 */
@Data
@Schema(description = "采购订单列表项")
public class PurchaseOrderVo {

    @Schema(description = "采购订单ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long purchaseOrderId;

    @Schema(description = "采购单号")
    private String purchaseNo;

    @Schema(description = "供应商ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long supplierId;

    @Schema(description = "供应商编码")
    private String supplierCode;

    @Schema(description = "供应商名称")
    private String supplierName;

    @Schema(description = "入库仓库ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long warehouseId;

    @Schema(description = "入库仓库名称")
    private String warehouseName;

    @Schema(description = "采购订单状态")
    private String status;

    @Schema(description = "订单总金额")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal totalAmount;

    @Schema(description = "预计到货日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expectedArrivalDate;

    @Schema(description = "创建人ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdById;

    @Schema(description = "创建人姓名")
    private String createdByName;

    @Schema(description = "提交时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime submittedAt;

    @Schema(description = "提交人ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long submittedById;

    @Schema(description = "提交人姓名")
    private String submittedByName;

    @Schema(description = "审核人ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long approvedById;

    @Schema(description = "审核人姓名")
    private String approvedByName;

    @Schema(description = "审核时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime approvedAt;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    @Schema(description = "乐观锁版本号")
    private Integer version;

    @Schema(description = "备注")
    private String remark;
}
