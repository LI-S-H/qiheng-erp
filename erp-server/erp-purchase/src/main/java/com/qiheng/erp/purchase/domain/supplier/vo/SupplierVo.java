package com.qiheng.erp.purchase.domain.supplier.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.qiheng.erp.common.config.BigDecimalNumberSerializer;
import com.qiheng.erp.common.config.MoneyStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 供应商响应。评分使用业务值，null 表示样本或必要输入不足。 */
@Data
@Schema(description = "供应商响应")
public class SupplierVo {
    @Schema(description = "对应 supplier.id，BIGINT 按字符串传输")
    @JsonSerialize(using = ToStringSerializer.class) private Long supplierId;

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

    @Schema(description = "综合评分；NULL 表示必需评分输入尚未齐备，数据库按 INT×100 保存")
    private BigDecimal overallScore;

    @Schema(description = "供应商交付评分；NULL 表示尚无已到承诺日的交付样本，数据库按 INT×100 保存")
    private BigDecimal deliveryScore;

    @Schema(description = "各供货关系质量分按样本金额加权的汇总分，数据库按 INT×100 保存")
    private BigDecimal qualityScore;

    @Schema(description = "各供货关系价格分按样本金额加权的汇总分，数据库按 INT×100 保存")
    private BigDecimal priceScore;

    @Schema(description = "人工服务评分；NULL 表示尚未设定，数据库按 INT×100 保存")
    private BigDecimal serviceScore;

    @Schema(description = "当前人工服务分原因；服务分为空时返回空字符串，初始值不写评分变化日志但原因保留")
    private String serviceScoreReason;

    /**
     * OpenAPI 要求 number/double，使用项目内无参序列化器避免 Spring 创建 Jackson 内部 NumberSerializer 失败。
     */
    @Schema(description = "完全入库订单按金额加权的平均到货周期，只作分析，不重复进入权重")
    @JsonSerialize(using = BigDecimalNumberSerializer.class)
    private BigDecimal avgDeliveryDays;

    @Schema(description = "最近 180 天有效已确认入库金额，金额字符串；无有效已确认入库样本时返回 null，数据库以分保存")
    @JsonSerialize(using = MoneyStringSerializer.class)
    private BigDecimal scoreBasisAmount;

    @Schema(description = "NOT_READY 表示样本或必需输入不足；READY 表示可参与自动推荐")
    private String scoreStatus;

    @Schema(description = "对应 supplier.status，1 启用，0 停用")
    private Integer status;

    @Schema(description = "Current supplier.version for optimistic locking.")
    private Integer version;

    @Schema(description = "对应 supplier.remark")
    private String remark;

    @Schema(description = "对应 supplier.create_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    @Schema(description = "对应 supplier.update_time")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime updateTime;

    @Schema(description = "对应 supplier.updated_by_id，BIGINT 按字符串传输；最后一次创建、编辑或启停操作的人")
    @JsonSerialize(using = ToStringSerializer.class) private Long updatedById;

    @Schema(description = "对应 supplier.updated_by_name；历史数据未记录时可为空")
    private String updatedByName;
}
