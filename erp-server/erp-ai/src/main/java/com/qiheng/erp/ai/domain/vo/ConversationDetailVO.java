package com.qiheng.erp.ai.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 会话详情：页面重新进入时据此恢复进度展示。
 *
 * <p>活动执行用于判断是否正在运行；当前轮次流水与匹配的检查点用于判断暂停或终态。
 * 用户可见历史由消息分页接口独立返回，不从 State 重建。</p>
 *
 * @param conversationId 会话 ID
 * @param title          会话标题
 * @param description    会话摘要
 * @param updatedAt      最近更新时间
 * @param running        是否有任务正在执行
 * @param runNode        当前运行节点编码，无运行为 null
 * @param runLabel       当前节点展示文案，无运行为 null
 * @param runStatus      当前任务状态，包括待补充、完成、失败或中断
 * @author Li
 * @since 2026-10-15
 */
@Schema(description = "AI 会话详情")
public record ConversationDetailVO(
        @Schema(description = "会话 ID") String conversationId,
        @Schema(description = "会话标题") String title,
        @Schema(description = "会话摘要") String description,
        @Schema(description = "最近更新时间") String updatedAt,
        @Schema(description = "是否有任务正在执行") boolean running,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "当前运行节点编码") String runNode,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        @Schema(description = "当前节点展示文案") String runLabel,
        @Schema(description = "当前任务状态") String runStatus
) {
}
