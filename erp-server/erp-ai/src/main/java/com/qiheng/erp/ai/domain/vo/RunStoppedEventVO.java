package com.qiheng.erp.ai.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 本轮停止事件体：{@code run_stopped} 事件载荷。
 *
 * <p>薄载荷只携带终态状态、可直接展示的说明与展示告警；
 * 失败提示正文同时由流水消息提供，前端收到后重新拉取消息列表。</p>
 *
 * @param status  停止状态：FORMAT_RETRY_EXHAUSTED 或 FAILED
 * @param reason  可直接展示的失败说明（业务异常文案），无额外说明时为 null
 * @param warning 保留字段：消息落库归节点，收尾不再产生展示级告警，当前恒为空
 * @author Li
 * @since 2026-10-18
 */
@Schema(description = "AI 任务停止事件")
public record RunStoppedEventVO(
        @Schema(description = "停止状态：FORMAT_RETRY_EXHAUSTED 或 FAILED") String status,
        @Schema(description = "可直接展示的失败说明，无额外说明时为空") String reason,
        @Schema(description = "保留字段，当前恒为空") String warning
) {
}
