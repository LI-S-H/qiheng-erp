package com.qiheng.erp.sales.domain.salesorder.vo;

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
 * 销售订单分页查询响应 VO
 * </p>
 *
 * @author Li
 * @since 2026-08-10
 */
@Data
@Schema(description = "销售订单分页查询响应")
public class SalesOrderVo {

    @Schema(description = "销售订单ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long salesOrderId;

    @Schema(description = "销售单号")
    private String salesNo;

    @Schema(description = "客户ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long customerId;

    @Schema(description = "客户编码")
    private String customerCode;

    @Schema(description = "客户名称")
    private String customerName;

    @Schema(description = "出库仓库ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long warehouseId;

    @Schema(description = "出库仓库名称")
    private String warehouseName;

    @Schema(description = "状态：DRAFT、SUBMITTED、APPROVED、PARTIAL_OUTBOUND、OUTBOUND_DONE、CANCELLED")
    private String status;

    @Schema(description = "订单总金额，按 100 倍存储值还原为业务小数")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal totalAmount;

    @Schema(description = "预计发货日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expectedDeliveryDate;

    @Schema(description = "库存锁定时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lockedAt;

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
