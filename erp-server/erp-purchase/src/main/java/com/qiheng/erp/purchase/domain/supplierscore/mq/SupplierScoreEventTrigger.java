package com.qiheng.erp.purchase.domain.supplierscore.mq;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qiheng.erp.purchase.domain.supplierscore.enums.TriggerType;
import lombok.Data;

import java.io.Serializable;

/**
 * 评分重算事件触发请求(业务线程合并入参)。
 *
 * <p>当前由首次完全入库的业务线程在 AFTER_COMMIT 阶段构造,
 * 传给 {@link com.qiheng.erp.purchase.service.support.ScoreRecalcPendingService#mergeAndScheduleFire},
 * 由该 Service 加锁、写/合并 Redis pending,决定是否投递 SCHEDULED_FIRE 延迟消息。</p>
 *
 * <p>不直接发送到 MQ，只用于 Service 方法传参；每日校正和报价到期由定时任务直接调用重算入口。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SupplierScoreEventTrigger implements Serializable {

    /** 目标供应商 ID */
    private Long supplierId;

    /** 当前 MQ 触发类型为 INBOUND_TRIGGER，复用评分触发枚举，避免字符串拼写错误。 */
    private TriggerType triggerType;

    /** 关联采购单 ID；合并批次保留全部采购单 ID，供消费者批量增量计算。 */
    private Long sourceRefId;

    /** 来源入库单号；pending 保留全部来源，消费日志使用首条来源单号。 */
    private String sourceRefNo;

    /** 事件发生时间，Unix 毫秒，用于来源审计。 */
    private Long occurredAt;
}
