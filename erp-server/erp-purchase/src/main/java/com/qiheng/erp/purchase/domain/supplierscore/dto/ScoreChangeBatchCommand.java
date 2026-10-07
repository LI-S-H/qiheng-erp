package com.qiheng.erp.purchase.domain.supplierscore.dto;

import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import lombok.Data;

import java.util.List;

/**
 * 日志批量写入入参。
 *
 * <p>由 SupplierScoreRecalculateService 在重算完成后构造,
 * 作为唯一日志写入入口 appendBatch() 的入参。
 * 一次重算产生的所有日志共享 batchNo。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
@Data
public class ScoreChangeBatchCommand {
    /** 批次号,由 BillNoGenerator 生成(SC + yyyyMMdd + 5 位序号) */
    private String batchNo;
    /** 规则版本，由重算入口明确提供 */
    private String ruleVersion;
    /** 供应商 ID */
    private Long supplierId;
    /** 触发来源枚举 */
    private TriggerType triggerType;
    /** 完整来源列表；同批次日志共享，统一校验后序列化落库。 */
    private List<ScoreChangeSource> relatedSources;
    /** 操作人类型:USER-人工,SYSTEM-系统任务，必须明确指定 */
    private String operatorType;
    /** 操作人 ID,SYSTEM 类型时为 null */
    private Long operatorId;
    /** 操作人姓名;USER 类型填用户姓名,SYSTEM 类型填场景描述(如 D2-每日兜底) */
    private String operatorName;
    /** 人类可读描述 */
    private String reason;
    /** 本批次所有日志条目 */
    private List<ScoreChangeLogEntry> entries;
}
