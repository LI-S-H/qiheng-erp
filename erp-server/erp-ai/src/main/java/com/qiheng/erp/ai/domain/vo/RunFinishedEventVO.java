package com.qiheng.erp.ai.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 本轮完成事件体：{@code run_finished} 事件载荷。
 *
 * <p>薄载荷只携带终态状态与展示告警；用户可见正文由流水消息提供，
 * 前端收到后重新拉取消息列表，消息流水是历史展示的唯一真相。</p>
 *
 * @param status  完成状态，当前为 PLAN_READY
 * @param warning 保留字段：消息落库归节点，收尾不再产生展示级告警，当前恒为空
 * @author Li
 * @since 2026-10-18
 */
@Schema(description = "AI 任务完成事件")
public record RunFinishedEventVO(
        @Schema(description = "完成状态，当前为 PLAN_READY") String status,
        @Schema(description = "保留字段，当前恒为空") String warning
) {
}
