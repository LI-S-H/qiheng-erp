package com.qiheng.erp.purchase.domain.supplierscore.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;
import com.qiheng.erp.purchase.domain.supplierscore.dto.ScoreChangeSource;
import java.util.List;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 单项评分改变及关联推荐分、供应商总分前后快照。 */
@Data
// 覆盖全局 non_null：无编号、系统用户ID和缺失评分必须明确返回 null，与前端契约一致。
@JsonInclude(JsonInclude.Include.ALWAYS)
public class SupplierScoreChangeLogVo {
    @JsonSerialize(using = ToStringSerializer.class) private Long scoreChangeLogId;
    @JsonSerialize(using = ToStringSerializer.class) private Long supplierId;
    @JsonSerialize(using = ToStringSerializer.class) private Long supplierProductId;
    private String metricType;
    private BigDecimal metricScoreBefore;
    private BigDecimal metricScoreAfter;
    private BigDecimal productRecommendScoreBefore;
    private BigDecimal productRecommendScoreAfter;
    private BigDecimal supplierOverallScoreBefore;
    private BigDecimal supplierOverallScoreAfter;
    private String triggerType;
    /** 批次号 */
    private String batchNo;
    /** 规则版本 */
    private String ruleVersion;
    /** 完整来源数组；businessId 为字符串，无来源返回空数组。 */
    private List<ScoreChangeSource> relatedSources;
    /** 操作人类型:USER / SYSTEM */
    private String operatorType;
    @JsonSerialize(using = ToStringSerializer.class) private Long operatorId;
    private String operatorName;
    private String reason;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") private LocalDateTime createTime;
}
