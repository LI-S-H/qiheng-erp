package com.qiheng.erp.returnorder.domain.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.qiheng.erp.common.config.MoneyStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** 退货单列表与详情头部。 */
@Data
@Schema(description = "退货单列表项响应")
public class ReturnOrderVo {
    @Schema(description = "退货单ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long returnOrderId;

    @Schema(description = "退货单编号")
    private String returnNo;

    @Schema(description = "退货类型，如 PURCHASE_RETURN-采购退货、SALE_RETURN-销售退货")
    private String returnType;

    @Schema(description = "来源订单ID（如采购订单ID）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long sourceOrderId;

    @Schema(description = "来源订单编号（如采购订单编号）")
    private String sourceOrderNo;

    @Schema(description = "往来单位ID（供应商ID/客户ID）")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long partyId;

    @Schema(description = "往来单位编码")
    private String partyCode;

    @Schema(description = "往来单位名称")
    private String partyName;

    @Schema(description = "仓库ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long warehouseId;

    @Schema(description = "仓库名称")
    private String warehouseName;

    @Schema(description = "预计执行日期")
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate expectedExecutionDate;

    @Schema(description = "处理方式，如 RETURN_TO_SUPPLIER-退供应商、REFUND_ONLY-仅退款等")
    private String handlingType;

    @Schema(description = "退货原因编码")
    private String reasonCode;

    @Schema(description = "退货原因说明")
    private String returnReason;

    @Schema(description = "退货单总金额")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal totalAmount;

    @Schema(description = "退货单状态：DRAFT-草稿、SUBMITTED-已提交、APPROVED-已审核、PARTIAL_EXECUTED-部分执行、COMPLETED-已完成、CANCELLED-已取消")
    private String status;

    @Schema(description = "状态变更说明（如取消原因、驳回原因）")
    private String statusReason;

    @Schema(description = "创建人ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long createdById;

    @Schema(description = "创建人姓名")
    private String createdByName;

    @Schema(description = "提交时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime submittedAt;

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

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "乐观锁版本号")
    private Integer version;
}
