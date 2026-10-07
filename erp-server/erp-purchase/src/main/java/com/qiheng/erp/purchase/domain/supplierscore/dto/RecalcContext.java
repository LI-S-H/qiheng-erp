package com.qiheng.erp.purchase.domain.supplierscore.dto;

import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 评分变化日志的批次与来源元数据。
 *
 * @author Li
 * @since 2026-09-23
 */
@Data
@Builder
public class RecalcContext {
    /** 重算批次号 */
    private String batchNo;
    /** 触发来源枚举 */
    private TriggerType triggerType;
    /** 完整业务来源；无来源使用空列表，不参与事实计算范围判断。 */
    private List<ScoreChangeSource> relatedSources;
    /** 操作人类型:USER-人工,SYSTEM-系统任务，必须明确指定 */
    private String operatorType;
    /** 操作人 ID,SYSTEM 类型时为 null */
    private Long operatorId;
    /** 操作人姓名;USER 类型填用户姓名,SYSTEM 类型填场景描述(如 D2-每日兜底) */
    private String operatorName;
    /** 人类可读描述 */
    private String reason;
}
