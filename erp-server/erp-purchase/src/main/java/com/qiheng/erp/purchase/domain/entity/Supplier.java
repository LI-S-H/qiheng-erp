package com.qiheng.erp.purchase.domain.entity;

import java.io.Serial;
import java.math.BigDecimal;

import com.baomidou.mybatisplus.annotation.*;

import java.time.LocalDateTime;
import java.io.Serializable;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * <p>
 * 供应商表
 * </p>
 *
 * @author Li
 * @since 2026-07-29
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("supplier")
@Schema(name="Supplier对象", description="供应商表")
public class Supplier implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Schema(description = "供应商ID")
    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "供应商编码")
    @TableField("supplier_code")
    private String supplierCode;

    @Schema(description = "供应商名称")
    @TableField("supplier_name")
    private String supplierName;

    @Schema(description = "联系人")
    @TableField("contact_name")
    private String contactName;

    @Schema(description = "联系电话")
    @TableField("contact_phone")
    private String contactPhone;

    @Schema(description = "地址")
    @TableField("address")
    private String address;

    @Schema(description = "付款条件")
    @TableField("payment_terms")
    private String paymentTerms;

    @Schema(description = "综合评分，放大100倍保存，10000表示100.00")
    @TableField("overall_score")
    private Integer overallScore;

    @Schema(description = "交付评分，放大100倍保存，10000表示100.00")
    @TableField("delivery_score")
    private Integer deliveryScore;

    @Schema(description = "质量评分，放大100倍保存，10000表示100.00")
    @TableField("quality_score")
    private Integer qualityScore;

    @Schema(description = "价格评分，放大100倍保存，10000表示100.00")
    @TableField("price_score")
    private Integer priceScore;

    @Schema(description = "服务评分，放大100倍保存，10000表示100.00")
    @TableField("service_score")
    private Integer serviceScore;

    @Schema(description = "平均交付天数")
    @TableField("avg_delivery_days")
    private BigDecimal avgDeliveryDays;

    @Schema(description = "准时交付率，放大100倍保存，10000表示100.00%")
    @TableField("on_time_rate")
    private Integer onTimeRate;

    @Schema(description = "到货合格率，放大100倍保存，10000表示100.00%")
    @TableField("qualified_rate")
    private Integer qualifiedRate;

    @Schema(description = "状态：1启用，0禁用")
    @TableField("status")
    private Integer status;

    @Schema(description = "创建时间")
    @TableField("create_time")
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField("update_time")
    private LocalDateTime updateTime;

    @Schema(description = "逻辑删除：0正常，1删除")
    @TableField("deleted")
    private Integer deleted;

    @Schema(description = "备注")
    @TableField("remark")
    private String remark;

    @Schema(description = "乐观锁版本号")
    @TableField("version")
    @Version
    private Integer version;


}
