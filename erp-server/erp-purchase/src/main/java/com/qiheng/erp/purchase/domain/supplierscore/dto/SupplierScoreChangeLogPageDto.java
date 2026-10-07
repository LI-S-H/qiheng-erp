package com.qiheng.erp.purchase.domain.supplierscore.dto;

import com.qiheng.erp.common.dto.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDateTime;

/** 评分变更记录分页参数；所有条件均可选，同时提供时按 AND 组合。 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SupplierScoreChangeLogPageDto extends PageQuery {
    /** 供应商 ID，精确匹配 */
    private String supplierId;
    /** 供货关系 ID，精确匹配 */
    private String supplierProductId;
    /** 指标类型筛选(PRICE / QUALITY / DELIVERY / SERVICE) */
    @Pattern(regexp = "PRICE|QUALITY|DELIVERY|SERVICE", message = "指标类型不合法")
    private String metricType;
    /** 触发来源筛选 */
    @Pattern(regexp = "PRICE_TRIGGER|SERVICE_TRIGGER|INBOUND_TRIGGER|QUOTE_EXPIRED_TRIGGER|DAILY_TRIGGER|MERGED", message = "触发来源不合法")
    private String triggerType;
    /** 批次号筛选(精确匹配) */
    @Size(max = 64, message = "批次号最多64个字符")
    private String batchNo;
    /** 创建时间范围起点 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime startTime;
    /** 创建时间范围终点 */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private LocalDateTime endTime;
}
