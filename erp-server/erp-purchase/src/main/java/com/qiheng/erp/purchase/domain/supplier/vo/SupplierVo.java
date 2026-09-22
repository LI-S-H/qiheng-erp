package com.qiheng.erp.purchase.domain.supplier.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.NumberSerializer;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.qiheng.erp.common.config.MoneyStringSerializer;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 供应商响应。评分使用业务值，null 表示样本或必要输入不足。 */
@Data
public class SupplierVo {
    @JsonSerialize(using = ToStringSerializer.class) private Long supplierId;
    private String supplierCode;
    private String supplierName;
    private String contactName;
    private String contactPhone;
    private String address;
    private String paymentTerms;
    private BigDecimal overallScore;
    private BigDecimal deliveryScore;
    private BigDecimal qualityScore;
    private BigDecimal priceScore;
    private BigDecimal serviceScore;
    private String serviceScoreReason;
    /** OpenAPI 要求 number/double:BigDecimal 默认可能输出为字符串,使用 NumberSerializer.instance 强制输出为数字。 */
    @JsonSerialize(using = NumberSerializer.class)
    private BigDecimal avgDeliveryDays;
    @JsonSerialize(using = MoneyStringSerializer.class) private BigDecimal scoreBasisAmount;
    private String scoreStatus;
    private Integer status;
    private Integer version;
    private String remark;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8") private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8") private LocalDateTime updateTime;
    @JsonSerialize(using = ToStringSerializer.class) private Long updatedById;
    private String updatedByName;
}
