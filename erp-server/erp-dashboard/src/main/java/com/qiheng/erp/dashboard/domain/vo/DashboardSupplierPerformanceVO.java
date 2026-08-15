package com.qiheng.erp.dashboard.domain.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * <p>
 * 工作台供应商履约评分 VO
 * </p>
 *
 * <p>供应商评分字段按 ×100 存储，接口展示为业务小数（保留 1 位）。</p>
 *
 * @author Li
 * @since 2026-08-15
 */
@Data
@Schema(description = "工作台供应商履约评分")
public class DashboardSupplierPerformanceVO {

    @Schema(description = "供应商 ID")
    @JsonSerialize(using = ToStringSerializer.class)
    private Long supplierId;

    @Schema(description = "供应商编码")
    private String supplierCode;

    @Schema(description = "供应商名称")
    private String supplierName;

    @Schema(description = "交付评分（百分制）")
    private Double deliveryScore;

    @Schema(description = "质量评分（百分制）")
    private Double qualityScore;

    @Schema(description = "准时交付率（百分制）")
    private Double onTimeRate;
}