package com.qiheng.erp.purchase.domain.supplierscore.dto;

import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import lombok.Builder;
import lombok.Data;
import java.util.List;

/**
 * 消费者或每日校正入口的评分重算上下文。
 *
 * <p>完全入库事件携带合并后的采购单集合；日志来源与事实计算范围分开，避免多单合并退回全量。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
@Data
@Builder
public class ScoreRecalcContext {

    /** 目标供应商 ID */
    private Long supplierId;
    /** 入库触发必填且非空的完成采购单集合；消费者去重，每日校正不需要此字段。 */
    private List<Long> completedOrderIds;
    /** 统一评分批次号：MQ 沿用 pending.batchNo；每日校正有实际变更时由日志入口生成。 */
    private String batchNo;
    /** 触发类型 */
    private TriggerType triggerType;
    /** 完整日志来源，与 completedOrderIds 事实计算范围独立；无来源使用空列表。 */
    private List<ScoreChangeSource> relatedSources;
    /** 操作人类型:USER / SYSTEM */
    private String operatorType;
    /** 操作人 ID,SYSTEM 类型时为 null */
    private Long operatorId;
    /** 操作人姓名,USER 类型填姓名,SYSTEM 类型填场景描述 */
    private String operatorName;
    /** 可读日志说明，可用于合并来源；为空时日志构造器生成默认说明。 */
    private String mergedReason;
    /** 业务事务模式:同步 SYNC / 异步 ASYNC / 定时 SCHEDULED */
    private String executionMode;

}
