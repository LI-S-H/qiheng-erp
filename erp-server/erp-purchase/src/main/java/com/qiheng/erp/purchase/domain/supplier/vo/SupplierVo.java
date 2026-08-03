package com.qiheng.erp.purchase.domain.supplier.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * <p>
 * 供应商分页查询响应 VO
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@Data
@Schema(description = "供应商分页查询响应")
public class SupplierVo {

    @Schema(description = "供应商ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long supplierId;

    @Schema(description = "供应商编码")
    private String supplierCode;

    @Schema(description = "供应商名称")
    private String supplierName;

    @Schema(description = "联系人")
    private String contactName;

    @Schema(description = "联系电话")
    private String contactPhone;

    @Schema(description = "地址")
    private String address;

    @Schema(description = "付款条件")
    private String paymentTerms;

    @Schema(description = "综合评分，0-100 业务值")
    private BigDecimal overallScore;

    @Schema(description = "交付评分，0-100 业务值")
    private BigDecimal deliveryScore;

    @Schema(description = "质量评分，0-100 业务值")
    private BigDecimal qualityScore;

    @Schema(description = "价格评分，0-100 业务值")
    private BigDecimal priceScore;

    @Schema(description = "服务评分，0-100 业务值")
    private BigDecimal serviceScore;

    @Schema(description = "平均交付天数")
    private BigDecimal avgDeliveryDays;

    @Schema(description = "准时交付率，0-100 业务值")
    private BigDecimal onTimeRate;

    @Schema(description = "到货合格率，0-100 业务值")
    private BigDecimal qualifiedRate;

    @Schema(description = "状态：1启用，0禁用")
    private Integer status;

    @Schema(description = "乐观锁版本号")
    private Integer version;

    @Schema(description = "备注")
    private String remark;

    @Schema(description = "创建时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    @Schema(description = "最后维护人ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long updatedById;

    @Schema(description = "最后维护人姓名")
    private String updatedByName;
}
