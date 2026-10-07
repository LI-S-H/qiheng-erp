package com.qiheng.erp.purchase.domain.supplierscore.dto;

import com.qiheng.erp.purchase.domain.supplierscore.enums.ScoreSourceBusinessType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一条评分来源；ID 与编号必须属于同一个业务对象，多单合并保留完整列表。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// 来源编号允许为空，但不能受全局 non_null 影响而省略必需的 businessNo 字段。
@JsonInclude(JsonInclude.Include.ALWAYS)
public class ScoreChangeSource {
    /** 业务对象类型。 */
    private ScoreSourceBusinessType businessType;
    /** 正整数 ID，以字符串传输避免前端大整数精度丢失。 */
    private String businessId;
    /** 同一对象的业务编号；没有独立编号的供货关系使用 null。 */
    private String businessNo;
}
