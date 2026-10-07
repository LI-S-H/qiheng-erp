package com.qiheng.erp.purchase.domain.supplierscore.mq;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

/**
 * 评分重算调度消息体。
 *
 * <p>由业务线程在 {@link com.qiheng.erp.purchase.service.support.ScoreRecalcPendingService#mergeAndScheduleFire}
 * 内投递,5 分钟延迟后由 {@link com.qiheng.erp.purchase.mq.SupplierScoreFireConsumer} 消费,
 * 执行实际的 recalcForSupplier。</p>
 *
 * <p>单一消息类型,合并窗口由业务线程在投递前完成(读/写 Redis pending),
 * Consumer 只负责"被调度后干活",不再参与合并。</p>
 *
 * @author Li
 * @since 2026-09-23
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SupplierScoreFireMessage implements Serializable {

    /** 固定 msgType=SCHEDULED_FIRE,便于后续扩展识别 */
    private String msgType = "SCHEDULED_FIRE";

    /** 目标供应商 ID,锁定维度 */
    private Long supplierId;

    /** 统一评分批次号，与 Redis pending.batchNo 和评分日志批次一致，用于窗口核对及比较删除。 */
    private String batchNo;

    /** 计划触发时间,调试与审计用 */
    private Long scheduledFireAt;
}
